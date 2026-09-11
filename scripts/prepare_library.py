#!/usr/bin/env python3
"""Build an offline library without changing the source books."""
import hashlib,json,re,shutil,zipfile,posixpath
from pathlib import Path
from urllib.parse import unquote
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'library'; OUT.mkdir(exist_ok=True)
def local(tag):return tag.rsplit('}',1)[-1]
def clean(s): return re.sub(r'\s+',' ',s or '').strip()
def resolve(base,href):return posixpath.normpath(posixpath.join(base,unquote(href)))
def from_filename(src):
    """Strip the trailing '(Author) (Source)' groups download sites append, for books with no usable metadata."""
    name=src.name
    for ext in ('.epub','.mobi','.pdf'):
        if name.lower().endswith(ext):name=name[:-len(ext)];break
    return clean(name.split(' (')[0]) or clean(src.stem) or src.stem
def epub(src,dest,orig=None):
    with zipfile.ZipFile(src) as z:
        for info in z.infolist():
            target=(dest/info.filename).resolve()
            if not target.is_relative_to(dest.resolve()):raise ValueError('Unsafe archive path')
        z.extractall(dest)
    container=ET.parse(dest/'META-INF/container.xml')
    opf=next(e.attrib['full-path'] for e in container.iter() if local(e.tag)=='rootfile')
    doc=ET.parse(dest/opf); base=posixpath.dirname(opf)
    title=next((clean(e.text) for e in doc.iter() if local(e.tag)=='title' and clean(e.text)),from_filename(orig or src))
    author=' / '.join(clean(e.text) for e in doc.iter() if local(e.tag)=='creator' and clean(e.text))
    manifest={e.attrib['id']:e.attrib for e in doc.iter() if local(e.tag)=='item'}
    chapters=[]
    for e in doc.iter():
        if local(e.tag)=='itemref' and e.attrib.get('linear','yes')!='no':
            a=manifest[e.attrib['idref']]; p=resolve(base,a['href'])
            if not (dest/p).is_file():raise ValueError('Missing chapter '+p)
            label=''
            try:
                page=ET.parse(dest/p)
                label=next((clean(''.join(x.itertext())) for x in page.iter() if local(x.tag) in ('h1','h2','h3') and clean(''.join(x.itertext()))),'')
                if not label:label=next((clean(x.text) for x in page.iter() if local(x.tag)=='title' and clean(x.text)),'')
            except ET.ParseError:pass
            chapters.append({'path':p,'title':label or '第 %d 节'%(len(chapters)+1)})
    toc=[]
    nav=next((a for a in manifest.values() if 'nav' in a.get('properties','').split()),None)
    if nav:
        try:
            navpath=resolve(base,nav['href']); nd=ET.parse(dest/navpath)
            navs=[e for e in nd.iter() if local(e.tag)=='nav']
            node=next((e for e in navs if any(v=='toc' for v in e.attrib.values())),navs[0] if navs else nd.getroot())
            toc=[{'title':clean(''.join(e.itertext())),'path':resolve(posixpath.dirname(navpath),e.attrib['href'])} for e in node.iter() if local(e.tag)=='a' and e.attrib.get('href')]
        except Exception:pass
    if not toc:
        ncx=next((a for a in manifest.values() if a.get('media-type')=='application/x-dtbncx+xml'),None)
        if ncx:
            try:
                np=resolve(base,ncx['href']); nd=ET.parse(dest/np)
                for e in nd.iter():
                    if local(e.tag)=='navPoint':
                        label=next((clean(''.join(x.itertext())) for x in e if local(x.tag)=='navLabel'),'')
                        ref=next((x.attrib.get('src') for x in e if local(x.tag)=='content'),None)
                        if ref:toc.append({'title':label,'path':resolve(posixpath.dirname(np),ref)})
            except Exception:pass
    cover=next((a for a in manifest.values() if 'cover-image' in a.get('properties','').split()),None)
    if not cover:
        cid=next((e.attrib.get('content') for e in doc.iter() if local(e.tag)=='meta' and e.attrib.get('name')=='cover'),None)
        cover=manifest.get(cid)
    if not cover:cover=next((a for a in manifest.values() if 'cover' in a.get('href','').lower() and a.get('media-type','').startswith('image/')),None)
    cp=resolve(base,cover['href']) if cover else ''
    if cp and not (dest/cp).is_file():cp=''
    if not chapters:raise ValueError('Empty spine')
    return dict(title=title,author=author,chapters=chapters,toc=toc or chapters,cover=cp)
def legacy_mobi(inp,dest,src):
    from bs4 import BeautifulSoup
    shutil.copytree(inp.parent,dest,dirs_exist_ok=True)
    raw=inp.read_text(encoding='utf8')
    parts=re.split(r'<mbp:pagebreak\s*/?>\s*(?:</mbp:pagebreak>)?',raw,flags=re.I)
    anchors={}
    for i,part in enumerate(parts):
        for aid in re.findall(r'id=["\']([^"\']+)',part):anchors[aid]='part-%04d.html'%i
    chapters=[]
    for i,part in enumerate(parts):
        soup=BeautifulSoup(part,'html.parser')
        for a in soup.find_all('a',href=True):
            if a['href'].startswith('#') and a['href'][1:] in anchors:a['href']=anchors[a['href'][1:]]+a['href']
        text=clean(soup.get_text(' ',strip=True)); name='part-%04d.html'%i
        (dest/name).write_text('<!doctype html><html><head><meta charset="utf-8"></head><body>'+str(soup)+'</body></html>',encoding='utf8')
        if not text and not soup.find(['img','svg']):continue
        chapters.append({'title':text[:55] or '插图 / 第 %d 节'%(i+1),'path':name})
    toc=[]
    if (dest/'toc.ncx').exists():
        nd=ET.parse(dest/'toc.ncx')
        for e in nd.iter():
            if local(e.tag)=='navPoint':
                label=next((clean(''.join(x.itertext())) for x in e if local(x.tag)=='navLabel'),'')
                ref=next((x.attrib.get('src','') for x in e if local(x.tag)=='content'),'')
                aid=ref.split('#')[-1]
                if aid in anchors:toc.append({'title':label,'path':anchors[aid]+'#'+aid})
    # Old MOBI contains a usable linked contents page even when NCX is absent.
    if not toc:
        soup=BeautifulSoup(parts[0],'html.parser')
        for a in soup.find_all('a',href=True):
            aid=a['href'].lstrip('#')
            if aid in anchors:toc.append({'title':clean(a.get_text()),'path':anchors[aid]+'#'+aid})
    cover=''
    opf=dest/'content.opf'
    if opf.exists():
        od=ET.parse(opf); cid=next((e.attrib.get('content') for e in od.iter() if local(e.tag)=='meta' and e.attrib.get('name')=='cover'),None)
        cover=next((e.attrib['href'] for e in od.iter() if local(e.tag)=='item' and e.attrib.get('id')==cid),'')
    return dict(title=from_filename(src),author='',chapters=chapters,toc=toc or chapters,cover=cover)
# A previous catalog is the fallback for any book that fails this run, so one bad
# download can never quietly empty the shelf.
CATALOG=OUT/'catalog.json'
prev={}
if CATALOG.exists():
    try:prev={b['id']:b for b in json.loads(CATALOG.read_text(encoding='utf8'))}
    except Exception:prev={}
books=[];failed=[]
for src in sorted((ROOT/'电子书汇总').iterdir()):
    suffix=src.suffix.lower()
    if suffix not in ('.epub','.mobi','.pdf'):continue
    print('Preparing',src.name,flush=True)
    dest=None;temp=None;fresh=False
    try:
        digest=hashlib.sha256(src.read_bytes()).hexdigest(); bid=digest[:16]; dest=OUT/bid
        fresh=not dest.exists(); dest.mkdir(exist_ok=True)
        b={'id':bid,'source':src.name,'sha256':digest,'format':suffix[1:].upper()}
        if suffix=='.pdf':
            shutil.copy2(src,dest/'book.pdf'); b.update(title=from_filename(src),author='',kind='pdf',path='book.pdf',cover='')
        else:
            inp=src
            if suffix=='.mobi':
                import mobi
                temp,converted=mobi.extract(str(src)); inp=Path(converted)
            b.update(epub(inp,dest,src) if inp.suffix.lower()=='.epub' else legacy_mobi(inp,dest,src));b['kind']='epub'
        books.append(b)
    except Exception as e:
        failed.append((src.name,repr(e)))
        print('  SKIPPED:',src.name,'->',e,flush=True)
        if dest is not None and fresh:
            shutil.rmtree(dest,ignore_errors=True)
        elif dest is not None and dest.exists() and dest.name in prev:
            books.append(prev[dest.name]); print('  kept the previous catalog entry for this book',flush=True)
    finally:
        if temp:shutil.rmtree(temp,ignore_errors=True)
CATALOG.write_text(json.dumps(books,ensure_ascii=False),encoding='utf8')
print('READY:',len(books),'books; chapters:',sum(len(b.get('chapters',[])) for b in books))
if failed:
    print('FAILED:',len(failed),'book(s) were skipped:')
    for name,err in failed:print('  -',name,'->',err)
known={b['id'] for b in books}
orphans=sorted(d.name for d in OUT.iterdir() if d.is_dir() and d.name not in known)
if orphans:print('NOTE:',len(orphans),'library folder(s) no longer match any source book (not deleted):',', '.join(orphans))
