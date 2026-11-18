import asyncio, json, os, tempfile
from island_push import IslandPushMiddleware

async def main():
    async def original(scope, receive, send):
        await send({'type':'http.response.start','status':204,'headers':[]})
        await send({'type':'http.response.body','body':b''})
    with tempfile.TemporaryDirectory() as directory:
        app=IslandPushMiddleware(original,read_token='reader',write_token='writer',db=os.path.join(directory,'events.db'))
        async def call(path,token,body=b'',method='POST'):
            sent=[]
            async def receive(): return {'type':'http.request','body':body}
            async def send(item): sent.append(item)
            await app({'type':'http','path':path,'method':method,'headers':[(b'authorization',('Bearer '+token).encode())]},receive,send)
            return sent
        assert (await call('/push','reader',b'{}'))[0]['status']==401
        assert (await call('/push','writer',b'not-json'))[0]['status']==400
        for kind in ('STATUS','MESSAGE'):
            result=await call('/push','writer',json.dumps({'type':kind,'text':'hello'}).encode())
            assert result[0]['status']==202
        assert len(app.rows(0))==2
        assert (await call('/health',''))[0]['status']==204
        assert (await call('/sse','writer',method='GET'))[0]['status']==401
        sent=[]; queue=asyncio.Queue()
        async def receive(): return await queue.get()
        async def send(item):
            sent.append(item)
            if b'id: 2' in item.get('body',b''): await queue.put({'type':'http.disconnect'})
        await asyncio.wait_for(app({'type':'http','path':'/sse','method':'GET','headers':[(b'authorization',b'Bearer reader'),(b'last-event-id',b'1')]},receive,send),3)
        assert b'id: 2' in b''.join(item.get('body',b'') for item in sent)
        print('PASS: auth separation, validation, event persistence, replay, disconnect, existing route passthrough')

asyncio.run(main())
