// Browser regression test for the reader shell (app/assets): paging, reflow, anchors, gestures.
// Serves app/assets plus a generated book the way MainActivity does, then drives it in Chromium.
//   npm i -g playwright   (or set PLAYWRIGHT=/path/to/playwright)
//   node tests/web/reader.test.js      CHROME=/path/to/chrome optional
// Chapter normalization (self-closing tags, charsets) lives in Java; see tests/HtmlTest.java.
'use strict';
const http=require('http'),fs=require('fs'),os=require('os'),path=require('path');
const {chromium}=require(process.env.PLAYWRIGHT||'playwright');
const ASSETS=path.join(__dirname,'..','..','app','assets');
const LIB=fs.mkdtempSync(path.join(os.tmpdir(),'shiye-web-'));
let fails=0;const ok=(c,msg,extra)=>{console.log((c?'PASS ':'FAIL ')+msg+(extra!==undefined?' '+JSON.stringify(extra):''));if(!c)fails++};

function fixture(){
 const dir=path.join(LIB,'b000000000000001','OEBPS');fs.mkdirSync(dir,{recursive:true});
 const paras=(tag,n)=>Array.from({length:n},(_,k)=>`<p>第${tag}-${k}段起始。天地玄黄，宇宙洪荒，日月盈昃，辰宿列张。寒来暑往，秋收冬藏。闰余成岁，律吕调阳。云腾致雨，露结为霜。金生丽水，玉出昆冈。</p>`).join('');
 const xhtml=(title,body)=>`<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${title}</title></head><body>${body}</body></html>`;
 fs.writeFileSync(path.join(dir,'ch1.xhtml'),xhtml('第1章','<h1>第1章</h1>'+paras('A',400)));
 fs.writeFileSync(path.join(dir,'ch2.xhtml'),xhtml('第2章',`<h1>第2章</h1><p>开头<a href="#inl">跳到行内锚点</a>。</p>${paras('B',200)}<h2 id="sec">第2章 第二节</h2>${paras('C',200)}<p>段落<a id="inl"></a>行内锚点</p>${paras('D',200)}`));
 fs.writeFileSync(path.join(dir,'ch3.xhtml'),xhtml('第3章','<h1>第3章</h1>'+paras('E',3)));
 // no doctype: the HTML parser lays this one out in quirks mode
 fs.writeFileSync(path.join(dir,'q.html'),'<html><head><title>q</title></head><body>'+paras('Q',157)+'</body></html>');
 fs.writeFileSync(path.join(LIB,'catalog.json'),JSON.stringify([{id:'b000000000000001',title:'测试之书',author:'测试',format:'EPUB',kind:'epub',cover:'',
  chapters:[{path:'OEBPS/ch1.xhtml',title:'第1章'},{path:'OEBPS/ch2.xhtml',title:'第2章'},{path:'OEBPS/ch3.xhtml',title:'第3章'}],
  toc:[{path:'OEBPS/ch1.xhtml',title:'第1章'},{path:'OEBPS/ch2.xhtml',title:'第2章'},{path:'OEBPS/ch2.xhtml#sec',title:'第2章 第二节'},{path:'OEBPS/ch2.xhtml#inl',title:'行内锚点'},{path:'OEBPS/ch3.xhtml',title:'第3章'}]}]));
}
function serve(){
 const types={'.html':'text/html','.xhtml':'text/html','.css':'text/css','.js':'text/javascript','.json':'application/json'};
 return new Promise(res=>{const s=http.createServer((q,r)=>{const p=decodeURIComponent(new URL(q.url,'http://x').pathname);
  const f=p.startsWith('/library/')?path.join(LIB,p.slice(9)):path.join(ASSETS,p==='/'?'index.html':p.slice(1));
  fs.readFile(f,(e,d)=>{if(e){r.writeHead(404,{'Content-Type':'text/plain; charset=UTF-8'});r.end('文件尚未导入');return}
   r.writeHead(200,{'Content-Type':(types[path.extname(f).toLowerCase()]||'application/octet-stream')+'; charset=UTF-8'});r.end(d)})});
  s.listen(0,'127.0.0.1',()=>res(s))});
}
(async()=>{
 fixture();const server=await serve();const base='http://127.0.0.1:'+server.address().port;
 const b=await chromium.launch(process.env.CHROME?{executablePath:process.env.CHROME}:{});
 const ctx=await b.newContext({viewport:{width:1280,height:800},deviceScaleFactor:2,hasTouch:true,isMobile:true});
 const p=await ctx.newPage();p.on('pageerror',e=>{console.log('PAGEERR',e.message);fails++});
 await p.goto(base+'/index.html');await p.waitForFunction(()=>books.length>0);
 await p.evaluate(()=>{window.__persists=0;const orig=persist;window.persist=function(){__persists++;orig()}});
 const wait=async()=>{await p.waitForFunction(()=>loaded&&frame.contentDocument.readyState==='complete');await p.waitForTimeout(350)};
 const pos=()=>p.evaluate(()=>{const m=metrics();const d=frame.contentDocument;const r=d.caretRangeFromPoint(frameGap/2+2,framePad+2);return {page:pageOf(m),pages:pagesIn(m),aligned:m.pos%m.step===0,text:r?(r.startContainer.textContent||'').slice(r.startOffset,r.startOffset+10):null,vh:frame.contentWindow.innerHeight}});
 // spot visible? check that spot char is on current page
 const spotVisible=()=>p.evaluate(()=>{if(!spot||!spot.node)return null;const pg=spot.node.nodeType===3?pageOfNode(spot.node,spot.offset):pageOfNode(spot.node);return pg===pageOf(metrics())});
 await p.evaluate(()=>{books[0].chapters.push({path:'OEBPS/q.html',title:'q'});openBook(books[0])});await wait();
 for(const ch of [0,3])for(const cols of [1,2]){
  await p.evaluate(([ch,cols])=>{state.cols=cols;applyTheme();loadChapter(ch,1)},[ch,cols]);await wait();
  const r=await p.evaluate(()=>{const m=metrics();return {pos:m.pos,max:m.max,step:m.step,next:$('next').disabled,lastLeft:Math.round([...frame.contentDocument.querySelectorAll('p')].pop().getClientRects()[0].left)}});
  ok(r.max%r.step===0&&r.pos===r.max&&r.lastLeft<r.step,`last page aligned ch${ch} cols${cols}`,r);
 }
 await p.evaluate(()=>{state.cols=1;applyTheme();loadChapter(0)});await wait();
 await p.evaluate(()=>gotoPage(Math.floor(pagesIn(metrics())*0.8)));
 const p0=await pos();
 // theme change on last page must not knock alignment
 await p.evaluate(()=>{gotoPage(pagesIn(metrics())-1);state.theme='sepia';applyTheme()});ok((await pos()).aligned,'theme change keeps last page aligned',await pos());
 await p.evaluate(p=>gotoPage(p),p0.page);
 // tap in zen: floating menus, no reflow
 const box=await p.locator('#page').boundingBox();
 await p.touchscreen.tap(box.x+box.width/2,box.y+box.height/2);await p.waitForTimeout(250);
 let q=await pos();ok(await p.evaluate(()=>document.body.classList.contains('menus'))&&q.vh===p0.vh&&q.page===p0.page&&q.text===p0.text,'tap floats menus without reflow',{p0,q});
 ok(await p.evaluate(()=>getComputedStyle($('prev')).display!=='none'&&$('prev').getBoundingClientRect().height>0),'bottom bar visible while floating');
 await p.touchscreen.tap(box.x+box.width/2,box.y+box.height/2);await p.waitForTimeout(250);
 ok(!(await p.evaluate(()=>document.body.classList.contains('menus'))),'second tap hides menus');
 // zen off/on via settings keeps the spot
 await p.evaluate(()=>toggleZen());await p.waitForTimeout(300);q=await pos();ok(await spotVisible(),'zen off keeps spot on screen',{p0,q});
 ok(q.vh<p0.vh,'zen off pins bars (shorter frame)',q.vh);
 await p.touchscreen.tap(box.x+box.width/2,box.y+box.height/2);await p.waitForTimeout(300);q=await pos();ok(await p.evaluate(()=>state.zen&&!document.body.classList.contains('menus')),'tap with pinned bars returns to full screen');ok(q.page===p0.page&&q.text===p0.text,'zen off+on returns to same page',{p0,q});
 // font +2 +2 -2 -2
 for(const d of [2,2]){await p.evaluate(d=>resizeFont(d),d);await p.waitForTimeout(100);ok(await spotVisible(),'font +'+d+' keeps spot visible',await pos())}
 for(const d of [-2,-2])await p.evaluate(d=>resizeFont(d),d);await p.waitForTimeout(300);
 q=await pos();ok(q.page===p0.page&&q.text===p0.text,'font +2+2-2-2 returns to same page',{p0,q});
 // columns
 await p.evaluate(()=>restyle(()=>{state.cols=2}));await p.waitForTimeout(100);ok(await spotVisible(),'2 cols keeps spot visible',await pos());
 await p.evaluate(()=>restyle(()=>{state.cols=1}));await p.waitForTimeout(300);q=await pos();ok(q.page===p0.page&&q.text===p0.text,'cols 1-2-1 returns',{p0,q});
 // rotation
 await p.setViewportSize({width:800,height:1280});await p.waitForTimeout(500);ok(await spotVisible(),'portrait keeps spot visible',await pos());ok((await pos()).aligned,'portrait aligned');
 await p.setViewportSize({width:1280,height:800});await p.waitForTimeout(500);q=await pos();ok(q.page===p0.page&&q.text===p0.text,'rotate back returns',{p0,q});
 // swipe turns page and marks new spot
 const cdp=await ctx.newCDPSession(p);const swipe=async(x1,x2)=>{const y=400;await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:x1,y}]});for(let i=1;i<=6;i++)await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x1+(x2-x1)*i/6,y}]});await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await p.waitForTimeout(250)};
 await swipe(900,300);q=await pos();ok(q.page===p0.page+1&&q.aligned,'left swipe next page',q);
 await swipe(300,900);q=await pos();ok(q.page===p0.page&&q.aligned,'right swipe prev page',q);
 // keys
 await p.keyboard.press('ArrowRight');q=await pos();ok(q.page===p0.page+1,'ArrowRight next',q);await p.keyboard.press('PageUp');q=await pos();ok(q.page===p0.page,'PageUp prev',q);
 // TOC anchors
 for(const [i,id] of [[2,'sec'],[3,'inl']]){await p.evaluate(i=>{showPanel('toc');$('tocList').querySelectorAll('button')[i].click()},i);await wait();
  const r=await p.evaluate(id=>{const el=frame.contentDocument.getElementById(id);const rc=el.getClientRects()[0]||el.getBoundingClientRect();return {left:Math.round(rc.left),vw:frame.contentWindow.innerWidth,aligned:metrics().pos%metrics().step===0}},id);
  ok(r.left>=0&&r.left<r.vw&&r.aligned,'TOC anchor #'+id+' on screen',r)}
 // in-chapter link
 await p.evaluate(()=>{loadChapter(1)});await wait();
 await p.evaluate(()=>frame.contentDocument.querySelector('a[href="#inl"]').click());await p.waitForTimeout(200);
 ok(await p.evaluate(()=>{const rc=frame.contentDocument.getElementById('inl').getClientRects()[0];return rc.left>=0&&rc.left<frame.contentWindow.innerWidth&&metrics().pos%metrics().step===0}),'in-chapter link lands on target page');
 // idle saves
 const n0=await p.evaluate(()=>__persists);await p.waitForTimeout(6500);const n1=await p.evaluate(()=>__persists);ok(n1===n0,'idle reading does not rewrite state',{n0,n1});
 // shelf scroll restore + long press
 await p.evaluate(()=>{goBack();for(let i=0;i<40;i++)books.push({...books[0],id:'c'+String(i).padStart(15,'0'),title:'书'+i,cover:''});renderShelf();scrollTo(0,1500)});
 const y0=await p.evaluate(()=>scrollY);await p.evaluate(()=>openBook(books[0]));await wait();await p.evaluate(()=>goBack());ok(await p.evaluate(()=>scrollY)===y0,'shelf scroll restored',{y0,y:await p.evaluate(()=>scrollY)});
 await p.evaluate(()=>{window.__asks=0;window.askRemove=()=>__asks++;scrollTo(0,0)});
 const card=await p.locator('.book').first().boundingBox();
 // WebView fires contextmenu ~400-500 ms into a long press, before our 600 ms timer
 await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:card.x+50,y:card.y+80}]});await p.waitForTimeout(450);
 await p.evaluate(()=>{document.querySelector('.book').dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true}))});
 await p.waitForTimeout(750);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await p.waitForTimeout(300);
 ok(await p.evaluate(()=>__asks)===1,'long press (contextmenu + timer) asks once',await p.evaluate(()=>__asks));
 ok(await p.evaluate(()=>$('reader').hidden),'long press does not open the book');
 // side cutout: frame shrinks, pages stay aligned, spot kept
 await p.evaluate(()=>{goBack();openBook(books[0]);loadChapter(0,0.5)});await wait();const s0=await pos();
 await p.evaluate(()=>{document.documentElement.style.setProperty('--safe-left','44px');document.documentElement.style.setProperty('--safe-right','0px')});await p.waitForTimeout(400);
 const s1=await p.evaluate(()=>({left:$('page').getBoundingClientRect().left,vw:frame.contentWindow.innerWidth,aligned:metrics().pos%metrics().step===0}));
 ok(s1.left===44&&s1.vw===1236&&s1.aligned&&await spotVisible(),'safe-left insets the reader and keeps pages aligned',s1);
 console.log(fails?`${fails} FAILED`:'ALL PASS');await b.close();server.close();fs.rmSync(LIB,{recursive:true,force:true});process.exit(fails?1:0)
})().catch(e=>{console.error(e);process.exit(1)});
