"""Wrap the existing ASGI app; keeps its port, MCP routes and lifespan intact.

Set ISLAND_READ_TOKEN, ISLAND_WRITE_TOKEN and ISLAND_DB on the server.
At the existing ASGI entry point: app = IslandPushMiddleware(app)
No third party dependencies. Requires Python 3.9+.
"""
import asyncio
import hmac
import json
import os
import sqlite3
import time
from urllib.parse import parse_qs
from contextlib import closing


class IslandPushMiddleware:
    def __init__(self, app, *, read_token=None, write_token=None, db=None):
        self.app = app
        self.read_token = read_token or os.environ.get('ISLAND_READ_TOKEN', '')
        self.write_token = write_token or os.environ.get('ISLAND_WRITE_TOKEN', '')
        self.db = db or os.environ.get('ISLAND_DB', '/data/island.sqlite3')
        if not self.read_token or not self.write_token:
            raise RuntimeError('Both island tokens must be configured')
        if self.read_token == self.write_token:
            raise RuntimeError('Use separate read and write tokens')
        with closing(sqlite3.connect(self.db)) as conn, conn:
            conn.execute('PRAGMA journal_mode=WAL')
            conn.execute('CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL, created REAL NOT NULL)')

    async def reply(self, send, status, data):
        body = json.dumps(data, ensure_ascii=False).encode()
        await send({'type': 'http.response.start', 'status': status,
                    'headers': [(b'content-type', b'application/json; charset=utf-8')]})
        await send({'type': 'http.response.body', 'body': body})

    def rows(self, last_id):
        with closing(sqlite3.connect(self.db)) as conn, conn:
            return conn.execute('SELECT id,payload FROM events WHERE id>? ORDER BY id LIMIT 100', (last_id,)).fetchall()

    def insert(self, payload):
        with closing(sqlite3.connect(self.db)) as conn, conn:
            cursor = conn.execute('INSERT INTO events(payload,created) VALUES(?,?)', (json.dumps(payload, ensure_ascii=False), time.time()))
            return cursor.lastrowid

    async def __call__(self, scope, receive, send):
        path = scope.get('path')
        if scope['type'] != 'http' or path not in ('/sse', '/push'):
            return await self.app(scope, receive, send)
        headers = dict(scope.get('headers', []))
        auth = headers.get(b'authorization', b'').decode()
        token = auth[7:] if auth.startswith('Bearer ') else ''
        if path == '/push':
            if scope['method'] != 'POST':
                return await self.reply(send, 405, {'error': 'POST required'})
            body = bytearray()
            while True:
                chunk = await receive()
                if chunk['type'] == 'http.disconnect':
                    return
                body.extend(chunk.get('body', b''))
                if len(body) > 65536:
                    return await self.reply(send, 413, {'error': 'Body too large'})
                if not chunk.get('more_body'):
                    break
            try:
                payload = json.loads(body)
                if not isinstance(payload, dict):
                    raise ValueError()
            except (ValueError, UnicodeDecodeError):
                return await self.reply(send, 400, {'error': 'JSON object required'})
            token = token or payload.pop('token', '')
            if not isinstance(token, str) or not hmac.compare_digest(token, self.write_token):
                return await self.reply(send, 401, {'error': 'Unauthorized'})
            kind = str(payload.get('type', '')).lower()
            text = payload.get('text', payload.get('content', ''))
            if kind not in ('status', 'message') or not isinstance(text, str) or len(text) > 24000:
                return await self.reply(send, 400, {'error': 'Invalid type or text'})
            event = {'type': kind, 'text': text}
            event_id = await asyncio.to_thread(self.insert, event)
            return await self.reply(send, 202, {'id': event_id, 'accepted': True})
        if scope['method'] != 'GET':
            return await self.reply(send, 405, {'error': 'GET required'})
        if not hmac.compare_digest(token, self.read_token):
            return await self.reply(send, 401, {'error': 'Unauthorized'})
        try:
            last_id = max(0, int(headers.get(b'last-event-id', b'0')))
        except ValueError:
            return await self.reply(send, 400, {'error': 'Invalid event ID'})
        # First connection shows the latest message only; reconnections replay missed events.
        live = parse_qs(scope.get('query_string', b'').decode()).get('live') == ['1']
        if live or b'last-event-id' not in headers:
            with closing(sqlite3.connect(self.db)) as conn, conn:
                last_id = max(0, conn.execute('SELECT COALESCE(MAX(id),0) FROM events').fetchone()[0] - (0 if live else 1))
        await send({'type': 'http.response.start', 'status': 200,
                    'headers': [(b'content-type', b'text/event-stream; charset=utf-8'),
                                (b'cache-control', b'no-cache'), (b'x-accel-buffering', b'no')]})
        disconnected = asyncio.create_task(receive())
        heartbeat = time.monotonic()
        try:
            while True:
                if disconnected.done():
                    if disconnected.result()['type'] == 'http.disconnect':
                        return
                    disconnected = asyncio.create_task(receive())
                for event_id, payload in await asyncio.to_thread(self.rows, last_id):
                    data = f'id: {event_id}\ndata: {payload}\n\n'.encode()
                    await send({'type': 'http.response.body', 'body': data, 'more_body': True})
                    last_id = event_id
                if time.monotonic() - heartbeat >= 20:
                    await send({'type': 'http.response.body', 'body': b': heartbeat\n\n', 'more_body': True})
                    heartbeat = time.monotonic()
                await asyncio.sleep(.3)
        finally:
            disconnected.cancel()
            try:
                await disconnected
            except asyncio.CancelledError:
                pass
