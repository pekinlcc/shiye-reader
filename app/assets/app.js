'use strict';
const $=id=>document.getElementById(id), frame=$('page');
const bridge=(typeof Reader!=='undefined')?Reader:null;
let books=[],state={},current=null,chapter=0,filter='all',loaded=false,appliedCols=1,pendingRatio=0,pendingAnchor='',saveTimer,toastTimer,userMoved=false,lastFocus=null;
try{state=JSON.parse(bridge.getState())}catch(e){try{state=JSON.parse(localStorage.getItem('reading')||'{}')}catch(e){}}
state.books=state.books||{};state.font=state.font||21;state.width=state.width||760;state.theme=state.theme||'paper';if(state.zen===undefined)state.zen=true;if(state.cols===undefined)state.cols=(state.width>=1100?2:1);
function persist(){const data=JSON.stringify(state);if(bridge)bridge.saveState(data);else localStorage.setItem('reading',data)}
function node(tag,text,cls){const e=document.createElement(tag);if(text)e.textContent=text;if(cls)e.className=cls;return e}
function url(path){return '/library/'+current.id+'/'+path.split('/').map(encodeURIComponent).join('/')}
function asset(book,path){return '/library/'+book.id+'/'+path.split('/').map(encodeURIComponent).join('/')}
function info(book){const p=state.books[book.id];if(!p)return '尚未开始';if(book.kind==='pdf'){let page=0;try{page=bridge.getPdfPage(book.id)}catch(e){}return '读到第 '+(page+1)+' 页'}const total=(book.chapters||[]).length||1;return '已读 '+Math.min(100,Math.round(((p.chapter||0)+(p.ratio||0))/total*100))+'%'}
/** Keeps the Android status/navigation bars in step with whatever surface is on screen. */
function syncChrome(){try{if(bridge&&bridge.setChrome)bridge.setChrome($('reader').hidden?'paper':state.theme)}catch(e){}}
function renderShelf(){
 $('count').textContent=books.length+' 本';$('summary').textContent=books.length+' 本珍藏 · 历史、科技与文明 · 随时离线阅读';
 const q=$('search').value.trim().toLowerCase();let found=books.filter(b=>(b.title+' '+b.author).toLowerCase().includes(q)&&(filter==='all'||filter==='reading'&&state.books[b.id]||b.kind===filter));
 found.sort($('sort').value==='recent'?(a,b)=>(state.books[b.id]?.time||0)-(state.books[a.id]?.time||0):(a,b)=>a.title.localeCompare(b.title,'zh-CN'));
 $('grid').replaceChildren();for(const b of found){const card=node('button',null,'book');card.setAttribute('aria-label','阅读 '+b.title);const cover=node('div',null,'cover');const fallback=node('div',b.title.slice(0,26),'fallback');cover.append(fallback);if(b.cover){const img=new Image();img.alt=b.title+' 封面';img.loading='lazy';img.src=asset(b,b.cover);img.onload=()=>fallback.remove();img.onerror=()=>img.remove();cover.append(img)}cover.append(node('span',b.format,'badge'));card.append(cover,node('h3',b.title),node('span',b.author||'私人藏书','author'),node('span',info(b),'status'));let hold=null,held=false;const startHold=()=>{held=false;clearTimeout(hold);hold=setTimeout(()=>{held=true;askRemove(b)},600)};const endHold=()=>clearTimeout(hold);card.addEventListener('touchstart',startHold,{passive:true});card.addEventListener('touchend',endHold,{passive:true});card.addEventListener('touchmove',endHold,{passive:true});card.addEventListener('touchcancel',endHold,{passive:true});card.oncontextmenu=e=>{e.preventDefault();askRemove(b)};card.onclick=()=>{if(held){held=false;return}openBook(b)};$('grid').append(card)}
 $('empty').hidden=found.length>0;$('empty').replaceChildren();if(books.length)$('empty').append('没有找到符合条件的书籍');else{$('empty').append('书架还是空的。');const b=node('button','＋ 导入第一本书');b.onclick=doImport;$('empty').append(document.createElement('br'),b)}
 const last=books.filter(b=>state.books[b.id]).sort((a,b)=>state.books[b.id].time-state.books[a.id].time)[0];$('continue').hidden=!last;if(last){const copy=node('div',null,'resume-copy');copy.append(node('small','CONTINUE READING'),node('strong',last.title),node('p',info(last)));const btn=node('button','继续阅读 →');btn.onclick=()=>openBook(last);$('continue').replaceChildren(copy,btn)}
}
function openBook(b){
 current=b;const saved=state.books[b.id]||{};state.books[b.id]={...saved,time:Date.now()};persist();
 if(b.kind==='pdf'){if(bridge&&bridge.openPdf)bridge.openPdf(b.id,b.title);else toast('PDF 需要在拾页应用内打开');return}
 chapter=Math.max(0,Math.min(saved.chapter||0,b.chapters.length-1));$('shelf').hidden=true;$('reader').hidden=false;$('bookTitle').textContent=b.title;applyTheme();loadChapter(chapter,saved.ratio||0);if(state.zen&&!state.zenHinted){state.zenHinted=1;persist();toast('全屏阅读中 · 点击页面可显示上下菜单栏')}}
function loadChapter(index,ratio=0,anchor=''){
 loaded=false;userMoved=false;chapter=index;pendingRatio=ratio;pendingAnchor=anchor;
 $('loading').textContent='正在翻开这一页…';$('loading').hidden=false;
 $('chapterTitle').textContent=current.chapters[chapter].title;$('prev').disabled=true;$('next').disabled=true;frame.src=url(current.chapters[chapter].path);updateProgress();
}
/** The shelf always reads light; theme and zen only apply to the reading surface. */
function paintBody(){const b=document.body,inReader=!$('reader').hidden;
 b.classList.toggle('dark',inReader&&state.theme==='dark');
 b.classList.toggle('sepia',inReader&&state.theme==='sepia');
 b.classList.toggle('zen',inReader&&!!state.zen)}
function toggleZen(){restyle(()=>{state.zen=!state.zen})}
function applyTheme(){paintBody();const fv=state.font+' px';if($('fontValue').textContent!==fv)$('fontValue').textContent=fv;document.querySelectorAll('[data-theme]').forEach(b=>{const on=b.dataset.theme===state.theme;b.classList.toggle('selected',on);b.setAttribute('aria-pressed',on)});document.querySelectorAll('[data-cols]').forEach(b=>{const on=+b.dataset.cols===state.cols;b.classList.toggle('selected',on);b.setAttribute('aria-pressed',on)});$('zenToggle').classList.toggle('selected',!!state.zen);$('zenToggle').setAttribute('aria-pressed',!!state.zen);if(loaded)stylePage();syncChrome()}
function stylePage(){
 const d=frame.contentDocument,w=frame.contentWindow;if(!d||!d.body||!w)return;
 let st=d.getElementById('readerStyle');
 if(!st){st=d.createElement('style');st.id='readerStyle';d.head.append(st)}
 const dark=state.theme==='dark',sepia=state.theme==='sepia',
  bg=dark?'#202721':sepia?'#efe5ce':'#f7f5ef',
  ink=dark?'#d7ddcf':sepia?'#493d2f':'#30392e';
 const VW=Math.max(240,w.innerWidth),VH=Math.max(240,w.innerHeight);
 // 页边留白由 column-gap 提供：左右各 gap/2，正文栏宽正好让每屏整数栏
 const gap=Math.round(Math.min(76,Math.max(30,VW*0.085)));
 const cols=(state.cols===2&&VW>=740)?2:1;appliedCols=cols;
 const contentW=VW-gap;
 const colW=Math.floor((contentW-(cols-1)*gap)/cols);
 const padV=Math.round(Math.min(42,Math.max(16,VH*0.035)));
 const colH=Math.max(80,VH-padV*2);
 st.textContent=`
html{height:100%!important;overflow:hidden!important;background:${bg}!important;-webkit-text-size-adjust:100%!important}
body{box-sizing:border-box!important;height:100%!important;width:${contentW}px!important;
 margin:0 0 0 ${gap/2}px!important;padding:${padV}px 0!important;
 column-width:${colW}px!important;column-gap:${gap}px!important;column-fill:auto!important;
 background:${bg}!important;color:${ink}!important;
 font-family:serif!important;font-size:${state.font}px!important;line-height:1.9!important;
 overflow-wrap:anywhere!important;orphans:2!important;widows:2!important}
body *{color:inherit!important;font-family:inherit!important;max-width:100%!important}
p,div,span,font,li,td{font-size:inherit!important;line-height:inherit!important}
p{margin:.55em 0!important}
h1,h2,h3{line-height:1.4!important;break-after:avoid-column!important;margin:.5em 0!important}
img,svg{max-width:100%!important;max-height:${colH}px!important;height:auto!important;object-fit:contain!important;break-inside:avoid-column!important}
figure,table,pre,blockquote{break-inside:avoid-column!important}
table{max-width:100%!important}
pre{white-space:pre-wrap!important}
a{color:${dark?'#abc49b':'#527945'}!important}
*{box-sizing:border-box}
`;
}
/** 横向滚动位置对齐到整页边界。 */
function snapPage(){const m=metrics();if(frame.contentWindow)frame.contentWindow.scrollTo(Math.round(m.pos/m.step)*m.step,0)}

frame.addEventListener('load',()=>{
 if(!current||$('reader').hidden||frame.contentWindow.location.pathname!==new URL(url(current.chapters[chapter].path),location.href).pathname)return;try{const d=frame.contentDocument;if(!d||!d.body)throw Error('正文无法加载');if(d.body.textContent.trim()==='文件尚未导入')throw Error('本章文件尚未导入');loaded=true;stylePage();const w=frame.contentWindow;
 // The deferred pass corrects for images/fonts that settle after load, but must never fight the reader.
 const restoringChapter=chapter;const restore=()=>{if(!current||chapter!==restoringChapter||!loaded||userMoved)return;if(pendingAnchor){const target=d.getElementById(pendingAnchor)||d.querySelector('[name="'+CSS.escape(pendingAnchor)+'"]');if(target){target.scrollIntoView();snapPage()}}else{const m=metrics();w.scrollTo(Math.round(pendingRatio*m.max/m.step)*m.step,0)}updateProgress();saveProgress()};
 requestAnimationFrame(restore);setTimeout(restore,250);$('loading').hidden=true;
 let touch=null;d.addEventListener('touchstart',e=>{if(e.touches.length===1)touch={x:e.touches[0].clientX,y:e.touches[0].clientY};else touch=null},{passive:true});d.addEventListener('wheel',()=>{userMoved=true},{passive:true});d.addEventListener('touchmove',e=>{if(!touch)return;const mx=e.touches[0].clientX-touch.x,my=e.touches[0].clientY-touch.y;if(Math.abs(mx)>8||Math.abs(my)>8)userMoved=true;if(Math.abs(mx)>Math.abs(my)*1.5&&Math.abs(mx)>25)e.preventDefault()},{passive:false});d.addEventListener('touchend',e=>{if(!touch)return;const dx=e.changedTouches[0].clientX-touch.x,dy=e.changedTouches[0].clientY-touch.y;const onLink=!!(e.target&&e.target.closest&&e.target.closest('a'));touch=null;const selecting=!!w.getSelection().toString();if(Math.abs(dx)>65&&Math.abs(dx)>Math.abs(dy)*1.5&&!selecting){turnPage(dx<0?1:-1);return}if(Math.abs(dx)<12&&Math.abs(dy)<12&&!onLink&&!selecting)toggleZen()},{passive:true});
 w.addEventListener('scroll',()=>{updateProgress();clearTimeout(saveTimer);saveTimer=setTimeout(saveProgress,180)});
 w.addEventListener('resize',()=>{if(!loaded)return;const r=ratio();stylePage();requestAnimationFrame(()=>{const m=metrics();w.scrollTo(Math.round(r*m.max/m.step)*m.step,0);updateProgress()})});
 d.addEventListener('click',e=>{const a=e.target.closest('a');if(!a)return;e.preventDefault();const href=a.getAttribute('href');if(!href)return;let target;try{target=new URL(href,w.location.href)}catch(err){toast('此链接无法打开');return}if(target.origin!==location.origin){toast('离线阅读中，不打开外部链接');return}const prefix='/library/'+current.id+'/';let path='';try{path=decodeURIComponent(target.pathname.slice(prefix.length))}catch(err){path=target.pathname.slice(prefix.length)}let hash='';try{hash=decodeURIComponent(target.hash.slice(1))}catch(err){hash=target.hash.slice(1)}let index=current.chapters.findIndex(c=>c.path===path);if(index>=0){saveProgress();loadChapter(index,0,hash)}else if(hash){const el=d.getElementById(hash);if(el)el.scrollIntoView();else toast('此链接不在正文目录中')}else toast('此链接不在正文目录中')});
 }catch(e){loaded=false;const l=$('loading');l.textContent='本章加载失败。';const back=node('button','‹ 返回书架');back.onclick=goBack;l.append(back);toast(e.message)}
});
function ratio(){if(!loaded)return pendingRatio;const m=metrics();return m.max?Math.max(0,Math.min(1,m.pos/m.max)):0}
function saveProgress(){if(!current||current.kind==='pdf'||!loaded||$('reader').hidden)return;state.books[current.id]={chapter,ratio:ratio(),time:Date.now()};persist()}
function metrics(){const d=frame.contentDocument?.scrollingElement;const step=Math.max(1,frame.contentWindow?.innerWidth||1);
 return {pos:d?.scrollLeft||0,max:Math.max(0,(d?.scrollWidth||0)-step),step}}
function pageOf(m){return Math.max(0,Math.round(m.pos/m.step))}
function pagesIn(m){return Math.max(1,Math.round(m.max/m.step)+1)}
function gotoPage(i){const m=metrics();frame.contentWindow.scrollTo(Math.max(0,Math.min(m.max,Math.round(i)*m.step)),0);updateProgress();saveProgress()}
function turnPage(direction){
 if(!current||!loaded)return;userMoved=true;const m=metrics();
 if(direction>0){if(m.pos<m.max-2)gotoPage(pageOf(m)+1);else if(chapter<current.chapters.length-1){saveProgress();loadChapter(chapter+1)}else toast('已经是全书最后一页')}
 else{if(m.pos>2)gotoPage(pageOf(m)-1);else if(chapter>0){saveProgress();loadChapter(chapter-1,1)}else toast('已经是全书第一页')}
}
function updateProgress(){if(!current||current.kind==='pdf')return;const m=metrics();const pages=pagesIn(m),page=Math.min(pages,pageOf(m)+1);const label='第 '+(chapter+1)+' / '+current.chapters.length+' 章 · '+page+' / '+pages+' 页';if($('progress').textContent!==label)$('progress').textContent=label;$('prev').disabled=!loaded||(chapter===0&&m.pos<=2);$('next').disabled=!loaded||(chapter===current.chapters.length-1&&m.pos>=m.max-2)}
function goBack(){if(!$('shade').hidden){closePanels();return}if(!$('reader').hidden){saveProgress();$('reader').hidden=true;$('shelf').hidden=false;loaded=false;frame.src='about:blank';paintBody();current=null;renderShelf();syncChrome()}else toast('已在书架')}
/** Called by the host when the native PDF surface closes — no book is open any more. */
function exitPdf(){closePanels();current=null;paintBody();renderShelf();syncChrome()}
/** Called by the host Back button. Returns true if the page consumed it, false to leave the app. */
function hostBack(){if(!$('shade').hidden){closePanels();return true}if(!$('reader').hidden){goBack();return true}return false}
function setInert(on){if(!('inert' in HTMLElement.prototype))return;$('shelf').inert=on;$('reader').inert=on}
function showPanel(id){lastFocus=document.activeElement;$(id).hidden=false;$('shade').hidden=false;setInert(true);const c=$(id).querySelector('.close');if(c)c.focus();if(id==='toc'){renderToc();setTimeout(()=>{const active=$('tocList').querySelector('.active');if(active)active.scrollIntoView({block:'center'})},50)}}
function closePanels(){const open=!$('shade').hidden;$('shade').hidden=true;$('toc').hidden=true;$('settings').hidden=true;setInert(false);if(open&&lastFocus&&lastFocus.focus)lastFocus.focus();lastFocus=null}
function renderToc(){const q=$('tocSearch').value.trim();$('tocList').replaceChildren();let shown=0;for(const entry of current.toc||[]){if(q&&!entry.title.includes(q))continue;const [path,...hash]=entry.path.split('#');const i=current.chapters.findIndex(c=>c.path===path);if(i<0)continue;const b=node('button',entry.title);if(i===chapter)b.className='active';b.onclick=()=>{saveProgress();loadChapter(i,0,hash.join('#'));closePanels()};$('tocList').append(b);shown++}if(!shown)$('tocList').append(node('p',q?'没有找到匹配的章节':'这本书没有可用目录','toc-empty'))}
function doImport(){if(bridge&&bridge.importBooks)bridge.importBooks();else toast('请在拾页应用内导入')}
function askRemove(b){if(bridge&&bridge.confirmRemove)bridge.confirmRemove(b.id,b.title);else toast('请在拾页应用内管理书籍')}
/** Called by the host while a book is being unpacked. */
function importProgress(msg){clearTimeout(toastTimer);$('toast').textContent=msg;$('toast').hidden=false}
/** Called by the host when the whole batch is finished. */
function importDone(count,err){
 const n=+count||0;
 reloadCatalog().then(()=>{
  if(err&&n)toast('已导入 '+n+' 本，另有失败：'+err);
  else if(err)toast('导入失败：'+err);
  else if(n)toast('已导入 '+n+' 本');
  else{clearTimeout(toastTimer);$('toast').hidden=true}
 })
}
/** Called by the host after a book is deleted from disk. */
function bookRemoved(id,err){
 if(err){toast('移除失败：'+err);return}
 delete state.books[id];persist();reloadCatalog().then(()=>toast('已移除'))
}
function reloadCatalog(){return fetch('/library/catalog.json?t='+Date.now()).then(r=>r.ok?r.json():[]).then(b=>{books=b;renderShelf()}).catch(()=>{})}
function toast(s){$('toast').textContent=s;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,2500)}
$('back').onclick=goBack;$('prev').onclick=()=>turnPage(-1);$('next').onclick=()=>turnPage(1);$('tocButton').onclick=()=>showPanel('toc');$('settingsButton').onclick=()=>showPanel('settings');$('shade').onclick=closePanels;document.querySelectorAll('.close').forEach(e=>e.onclick=closePanels);$('tocSearch').oninput=renderToc;$('search').oninput=renderShelf;$('sort').onchange=renderShelf;$('importBtn').onclick=doImport;
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!$('shade').hidden){e.preventDefault();closePanels()}});
/** Re-styles the open chapter, then puts the reader back on the same line and refreshes the pager. */
function restyle(mutate){
 const r=ratio();mutate();applyTheme();persist();if(!loaded)return;
 requestAnimationFrame(()=>{if(!loaded||!frame.contentWindow||!frame.contentDocument)return;const m=metrics();frame.contentWindow.scrollTo(Math.round(r*m.max/m.step)*m.step,0);updateProgress();saveProgress()});
}
function resizeFont(delta){restyle(()=>{state.font=Math.max(15,Math.min(36,state.font+delta))})}
$('zenToggle').onclick=toggleZen;$('smaller').onclick=()=>resizeFont(-2);$('larger').onclick=()=>resizeFont(2);document.querySelectorAll('[data-theme]').forEach(b=>b.onclick=()=>{state.theme=b.dataset.theme;applyTheme();persist()});document.querySelectorAll('[data-cols]').forEach(b=>b.onclick=()=>{restyle(()=>{state.cols=+b.dataset.cols});setTimeout(()=>{if(state.cols===2&&appliedCols===1)toast('屏幕较窄，已按单栏排版')},280)});document.querySelectorAll('[data-filter]').forEach(b=>b.onclick=()=>{filter=b.dataset.filter;document.querySelectorAll('[data-filter]').forEach(c=>{c.classList.toggle('selected',c===b);c.setAttribute('aria-pressed',c===b)});renderShelf()});
setInterval(saveProgress,3000);document.addEventListener('visibilitychange',saveProgress);
syncChrome();
fetch('/library/catalog.json').then(r=>{if(!r.ok)throw Error('书架还是空的');return r.json()}).then(b=>{books=b;renderShelf()}).catch(e=>{$('summary').textContent=e.message;books=[];renderShelf()});
