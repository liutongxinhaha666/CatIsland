import json
import os
import ssl

from island_push import IslandPushMiddleware


async def health(scope, receive, send):
    if scope["type"] == "lifespan":
        while True:
            event = await receive()
            await send({"type": event["type"] + ".complete"})
            if event["type"] == "lifespan.shutdown":
                return
    if scope["type"] != "http":
        return
    if scope["method"] == "GET" and scope["path"] == "/health":
        expires = ssl._ssl._test_decode_cert(os.environ["ISLAND_TLS_CERT"])["notAfter"]
        body = json.dumps({"status": "ok", "certificate_expires": expires}).encode()
        status = 200
    else:
        body, status = b'{"error":"Not found"}', 404
    await send({"type": "http.response.start", "status": status,
                "headers": [(b"content-type", b"application/json")]})
    await send({"type": "http.response.body", "body": body})


app = IslandPushMiddleware(health)
