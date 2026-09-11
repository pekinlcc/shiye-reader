import json,time
from pathlib import Path
from device_test import Device
d=Device();Path('verification/user-state-before-tests.json').write_text(d.evaluate('JSON.stringify(state)'))
books=d.evaluate('books.map(b=>({id:b.id,title:b.title,kind:b.kind,format:b.format}))')
results=[]
for b in books:
 if b['kind']=='pdf':continue
 d.evaluate('goBack();openBook(books.find(b=>b.id=='+json.dumps(b['id'])+'))');d.wait()
 r=d.evaluate('({title:current.title,chapter,body:frame.contentDocument.body.textContent.length,images:frame.contentDocument.images.length,missing:[...frame.contentDocument.images].filter(i=>i.complete&&!i.naturalWidth).length,loaded})')
 assert r['body']>0 or r['images']>0,r
 results.append(r)
print(f'All {len(results)} EPUB/MOBI book opening checks passed')
# Exercise actual shared page buttons in both an EPUB and each MOBI variant.
for bid in [next(b['id'] for b in books if b['format']=='EPUB')]+[b['id'] for b in books if b['format']=='MOBI']:
 d.evaluate('goBack();openBook(books.find(b=>b.id=='+json.dumps(bid)+'))');d.wait()
 # Find a long chapter to test intra-chapter paging.
 found=False
 for ch in range(min(20,d.evaluate('current.chapters.length'))):
  d.evaluate(f'loadChapter({ch})');d.wait()
  if d.evaluate('metrics().max>metrics().step*1.5'):found=True;break
 assert found,'No long chapter found'
 initial=d.evaluate('({chapter,top:metrics().top,max:metrics().max,step:metrics().step})')
 d.evaluate("$('next').click()");time.sleep(.2);after=d.evaluate('({chapter,top:metrics().top})')
 assert after['chapter']==initial['chapter'] and after['top']>0,(initial,after)
 d.evaluate("$('prev').click()");time.sleep(.2);back=d.evaluate('({chapter,top:metrics().top})');assert back['chapter']==initial['chapter'] and back['top']<2,back
 # Repeat forward/back several times.
 for _ in range(3):
  d.evaluate("$('next').click()");time.sleep(.1)
 for _ in range(3):
  d.evaluate("$('prev').click()");time.sleep(.1)
 assert d.evaluate('chapter')==ch
 # Cross forward to next chapter and back to previous chapter end.
 d.evaluate('frame.contentWindow.scrollTo(0,metrics().max);updateProgress()');d.evaluate("$('next').click()");d.wait();assert d.evaluate('chapter')==ch+1
 d.evaluate("$('prev').click()");d.wait();assert d.evaluate('chapter')==ch
 assert d.evaluate('Math.abs(metrics().top-metrics().max)<3')
 # Bookmark return to shelf and reopen.
 d.evaluate('frame.contentWindow.scrollTo(0,metrics().max*.4);saveProgress()');saved=d.evaluate('ratio()');d.evaluate('goBack();openBook(books.find(b=>b.id=='+json.dumps(bid)+'))');d.wait();assert abs(d.evaluate('ratio()')-saved)<.01
 # Start/end boundaries.
 d.evaluate('loadChapter(0)');d.wait();assert d.evaluate("$('prev').disabled")
 d.evaluate('loadChapter(current.chapters.length-1,1)');d.wait();assert d.evaluate("$('next').disabled")
 print('PASS page buttons, repeat, chapter crossing, shelf resume, boundaries:',bid)
Path('verification/opening-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
