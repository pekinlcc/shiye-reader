import json,time,urllib.request,websocket
class Device:
 def __init__(self):
  pages=json.load(urllib.request.urlopen('http://127.0.0.1:9223/json'))
  self.ws=websocket.create_connection(next(p['webSocketDebuggerUrl'] for p in pages if 'reader.local' in p['url']),suppress_origin=True);self.n=0
 def evaluate(self,code):
  self.n+=1;self.ws.send(json.dumps({'id':self.n,'method':'Runtime.evaluate','params':{'expression':code,'returnByValue':True,'awaitPromise':True}}))
  while True:
   r=json.loads(self.ws.recv())
   if r.get('id')==self.n:
    if r.get('result',{}).get('exceptionDetails'):raise Exception(r)
    return r.get('result',{}).get('result',{}).get('value')
 def wait(self):
  for i in range(100):
   if self.evaluate('loaded && frame.contentDocument.readyState==="complete"'):time.sleep(.4);return
   time.sleep(.1)
  raise Exception('chapter load timeout')
if __name__=='__main__':
 d=Device();print(d.evaluate('({count:books.length,current:current?.title,state})'))
