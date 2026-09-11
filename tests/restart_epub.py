import sys,json,time,subprocess
from pathlib import Path
sys.path.insert(0,'scripts')
from device_test import Device
ADB='tools/platform-tools/adb'
def connect():
 for _ in range(50):
  pid=subprocess.check_output([ADB,'shell','pidof','com.pekinlcc.reader']).decode().strip()
  if pid:
   subprocess.run([ADB,'forward','tcp:9223','localabstract:webview_devtools_remote_'+pid],stdout=subprocess.DEVNULL)
   try:return Device()
   except Exception:pass
  time.sleep(.2)
 raise Exception('Cannot connect')
d=connect()
ids=d.evaluate("books.filter(b=>b.format==='MOBI').map(b=>b.id)")
ids+=d.evaluate("books.filter(b=>b.format==='EPUB').map(b=>b.id)")[:1]
saved={}
for bid in ids:
 d.evaluate('goBack();openBook(books.find(b=>b.id=='+json.dumps(bid)+'))');d.wait()
 for ch in range(20):
  d.evaluate(f'loadChapter({ch})');d.wait()
  if d.evaluate('metrics().max>300'):break
 d.evaluate('frame.contentWindow.scrollTo(0,metrics().max*.43);saveProgress()');saved[bid]=d.evaluate('({chapter,ratio:ratio()})')
subprocess.run([ADB,'shell','am','force-stop','com.pekinlcc.reader']);subprocess.run([ADB,'shell','am','start','-n','com.pekinlcc.reader/.MainActivity']);time.sleep(.6);d=connect()
for bid,p in saved.items():
 d.evaluate('goBack();openBook(books.find(b=>b.id=='+json.dumps(bid)+'))');d.wait();actual=d.evaluate('({chapter,ratio:ratio()})');assert actual['chapter']==p['chapter'] and abs(actual['ratio']-p['ratio'])<.01,(p,actual);print('PASS process restart restores chapter and scroll',bid,actual,flush=True)
# Real swipe input goes through the iframe touch handlers.
d.evaluate('loadChapter(9)');d.wait();d.evaluate('frame.contentWindow.scrollTo(0,0);updateProgress()');before=d.evaluate('({chapter,top:metrics().top})')
subprocess.run([ADB,'shell','input','swipe','1300','1400','400','1400','300']);time.sleep(.5);after=d.evaluate('({chapter,top:metrics().top})');assert after!=before,(before,after);print('PASS EPUB physical left swipe',flush=True)
subprocess.run([ADB,'shell','input','swipe','400','1400','1300','1400','300']);time.sleep(.5);back=d.evaluate('({chapter,top:metrics().top})');assert back==before,(before,back);print('PASS EPUB physical right swipe',flush=True)
# Directory button and entry, theme and font controls.
d.evaluate("$('tocButton').click();$('tocSearch').value='';renderToc();$('tocList').querySelectorAll('button')[10].click()");d.wait();assert d.evaluate("$('toc').hidden && loaded");print('PASS table of contents click',flush=True)
d.evaluate("$('settingsButton').click();document.querySelector('[data-theme=dark]').click();$('larger').click();closePanels()");assert d.evaluate("document.body.classList.contains('dark') && frame.contentDocument.getElementById('readerStyle').textContent.includes('#202721')");print('PASS night mode and font adjustment',flush=True)
subprocess.run([ADB,'exec-out','screencap','-p'],stdout=open('verification/epub-night.png','wb'))
# Restore the real reading history saved before automated navigation tests.
d.evaluate('goBack();state='+Path('verification/user-state-before-tests.json').read_text()+';persist();renderShelf()');print('Restored user reading history',flush=True)
