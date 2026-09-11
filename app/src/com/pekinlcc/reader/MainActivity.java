package com.pekinlcc.reader;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.webkit.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class MainActivity extends Activity {
    WebView web; PdfCanvas pdfCanvas; Button pdfPrev,pdfNext; boolean pdfLoading; File library; android.content.SharedPreferences prefs;
    volatile PdfRenderer pdf; ParcelFileDescriptor pdfFd; int pdfPage; String pdfId; TextView pageLabel;
    LinearLayout pdfLayout; final ExecutorService worker=Executors.newSingleThreadExecutor(); int renderVersion=0;
    final Object pdfLock=new Object();
    LinearLayout pdfTop,pdfBottom; String chromeTheme="paper"; Object backCallback; int safeTop=0,safeBottom=0;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("reading",MODE_PRIVATE); library=new File(getExternalFilesDir(null),"library");library.mkdirs();
        chromeTheme="paper";
        if(Build.VERSION.SDK_INT>=28){
            WindowManager.LayoutParams lp=getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode=Build.VERSION.SDK_INT>=30
                ?WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                :WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0);
        web=new WebView(this);web.setBackgroundColor(Color.rgb(247,245,239));
        web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(),"Reader");
        web.setOnApplyWindowInsetsListener((v,insets)->{measureInsets(insets);return insets;});
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                Uri u=r.getUrl();try {
                    if(!"reader.local".equals(u.getHost()))return response("text/plain",null,new ByteArrayInputStream(new byte[0]));
                    String path=u.getPath(); if(path==null||path.isEmpty())path="/";
                    InputStream stream;
                    if(path.startsWith("/library/")){File f=new File(library,path.substring(9));if(!f.getCanonicalPath().startsWith(library.getCanonicalPath()+File.separator))throw new IOException("outside library");stream=new FileInputStream(f);}
                    else stream=getAssets().open(path.equals("/")?"index.html":path.substring(1));
                    String m=mime(path);
                    if(m==null){BufferedInputStream b=new BufferedInputStream(stream,2048);m=sniff(b);stream=b;}
                    return response(m,charset(m),stream);
                }catch(Exception e){return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",Collections.emptyMap(),new ByteArrayInputStream("文件尚未导入".getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
            }
            @Override public void onPageFinished(WebView v,String u){pushSafeArea();}
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !"reader.local".equals(r.getUrl().getHost());}
        });
        setContentView(web);web.loadUrl("https://reader.local/index.html");
        if(Build.VERSION.SDK_INT>=33)backCallback=BackCompat.register(this);
    }
    /** Isolated so the API 33 types are never resolved on older devices. */
    static class BackCompat {
        static Object register(MainActivity a){
            android.window.OnBackInvokedCallback cb=a::handleBack;
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,cb);
            return cb;
        }
        static void unregister(MainActivity a,Object cb){
            a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback)cb);
        }
    }

    /** The window now covers the cutout, so the page must inset its own content instead. */
    void measureInsets(android.view.WindowInsets insets){
        int top=0,bottom=0;
        if(Build.VERSION.SDK_INT>=30){
            top=insets.getInsets(android.view.WindowInsets.Type.displayCutout()).top;
            bottom=insets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures()).bottom;
        }else if(Build.VERSION.SDK_INT>=28){
            android.view.DisplayCutout c=insets.getDisplayCutout();
            if(c!=null)top=c.getSafeInsetTop();
        }
        if(top==safeTop&&bottom==safeBottom)return;
        safeTop=top;safeBottom=bottom;pushSafeArea();
        if(pdfLayout!=null)pdfLayout.setPadding(0,safeTop,0,safeBottom);
    }
    void pushSafeArea(){
        float d=getResources().getDisplayMetrics().density;
        web.evaluateJavascript("document.documentElement.style.setProperty('--safe-top','"+(safeTop/d)
            +"px');document.documentElement.style.setProperty('--safe-bottom','"+(safeBottom/d)+"px')",null);
    }
    WebResourceResponse response(String mime,String enc,InputStream data){Map<String,String> h=new HashMap<>();h.put("Content-Security-Policy","default-src 'self' data: blob:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-src 'self'");return new WebResourceResponse(mime,enc,200,"OK",h,data);}

    /** Returns null when the extension is unknown, so the caller can sniff the content instead. */
    static String mime(String p){String s=p.toLowerCase(Locale.ROOT);
        if(s.endsWith(".html")||s.endsWith(".xhtml")||s.endsWith(".htm"))return "text/html";
        if(s.endsWith(".css"))return "text/css";
        if(s.endsWith(".js"))return "text/javascript";
        if(s.endsWith(".json"))return "application/json";
        if(s.endsWith(".png"))return "image/png";
        if(s.endsWith(".svg"))return "image/svg+xml";
        if(s.endsWith(".jpg")||s.endsWith(".jpeg"))return "image/jpeg";
        if(s.endsWith(".gif"))return "image/gif";
        if(s.endsWith(".webp"))return "image/webp";
        if(s.endsWith(".bmp"))return "image/bmp";
        if(s.endsWith(".woff2"))return "font/woff2";
        if(s.endsWith(".woff"))return "font/woff";
        if(s.endsWith(".ttf"))return "font/ttf";
        if(s.endsWith(".otf"))return "font/otf";
        if(s.endsWith(".txt"))return "text/plain";
        if(s.endsWith(".xml")||s.endsWith(".opf")||s.endsWith(".ncx"))return "application/xml";
        return null;
    }

    static String charset(String mime){return mime!=null&&(mime.startsWith("text/")||mime.equals("application/xml")||mime.equals("application/json")||mime.equals("image/svg+xml"))?"UTF-8":null;}

    /** Many EPUBs ship spine documents with no file extension; type them by content instead of guessing binary. */
    static String sniff(BufferedInputStream in) throws IOException {
        in.mark(2048);byte[] buf=new byte[512];int n=0;
        while(n<buf.length){int r=in.read(buf,n,buf.length-n);if(r<0)break;n+=r;}
        in.reset();
        if(n>=4){
            if((buf[0]&0xff)==0x89&&buf[1]=='P'&&buf[2]=='N'&&buf[3]=='G')return "image/png";
            if((buf[0]&0xff)==0xFF&&(buf[1]&0xff)==0xD8)return "image/jpeg";
            if(buf[0]=='G'&&buf[1]=='I'&&buf[2]=='F')return "image/gif";
            if(n>=12&&buf[0]=='R'&&buf[1]=='I'&&buf[2]=='F'&&buf[3]=='F'&&buf[8]=='W'&&buf[9]=='E'&&buf[10]=='B'&&buf[11]=='P')return "image/webp";
        }
        String head=new String(buf,0,n,java.nio.charset.StandardCharsets.UTF_8);
        int i=0;while(i<head.length()&&(head.charAt(i)=='﻿'||Character.isWhitespace(head.charAt(i))))i++;
        String low=head.substring(i).toLowerCase(Locale.ROOT);
        if(low.startsWith("<?xml")){
            if(low.contains("<html")||low.contains("xhtml")||low.contains("<!doctype html"))return "text/html";
            if(low.contains("<svg"))return "image/svg+xml";
            return "application/xml";
        }
        if(low.startsWith("<!doctype html")||low.startsWith("<html")||low.startsWith("<!--")||low.contains("<html"))return "text/html";
        if(low.startsWith("<svg"))return "image/svg+xml";
        if(low.startsWith("<"))return "application/xml";
        return "application/octet-stream";
    }

    class Bridge {
        @JavascriptInterface public String getState(){return prefs.getString("state","{}");}
        @JavascriptInterface public void saveState(String s){if(s.length()<2000000)prefs.edit().putString("state",s).commit();}
        @JavascriptInterface public void openPdf(String id,String title){if(id!=null&&id.matches("[a-f0-9]{16}"))runOnUiThread(()->showPdf(id,title));}
        @JavascriptInterface public int getPdfPage(String id){return prefs.getInt("pdf_"+id,0);}
        @JavascriptInterface public void reload(){runOnUiThread(()->web.reload());}
        /** Called by the page so the system bars follow whatever surface is on screen. */
        @JavascriptInterface public void setChrome(String theme){final String t=theme;runOnUiThread(()->{if(pdf==null)applyChrome(t);});}
    }

    // ---- theming -------------------------------------------------------------
    static boolean isDark(String t){return "dark".equals(t);}
    static int bgOf(String t){return "dark".equals(t)?Color.rgb(32,39,33):"sepia".equals(t)?Color.rgb(239,229,206):Color.rgb(247,245,239);}
    static int inkOf(String t){return "dark".equals(t)?Color.rgb(217,223,211):"sepia".equals(t)?Color.rgb(73,61,47):Color.rgb(37,78,67);}
    static int pressOf(String t){return "dark".equals(t)?Color.rgb(52,63,53):"sepia".equals(t)?Color.rgb(224,211,183):Color.rgb(224,230,219);}
    static int matOf(String t){return "dark".equals(t)?Color.rgb(24,29,25):"sepia".equals(t)?Color.rgb(222,209,181):Color.rgb(228,229,224);}

    /** Hides the status and navigation bars; a swipe from either edge shows them transiently. */
    void applyChrome(String theme){
        chromeTheme=theme;Window w=getWindow();
        if(w.peekDecorView()==null)return; // decor not installed yet; onAttachedToWindow re-applies
        int bg=bgOf(theme);boolean dark=isDark(theme);
        w.setStatusBarColor(bg);w.setNavigationBarColor(bg);
        if(Build.VERSION.SDK_INT>=30){
            android.view.WindowInsetsController c=w.getInsetsController();
            if(c!=null){
                int lightBars=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark?0:lightBars,lightBars);
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(android.view.WindowInsets.Type.systemBars());
            }
        }else{
            int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                     |View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            if(!dark)flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }
    @Override public void onAttachedToWindow(){super.onAttachedToWindow();applyChrome(chromeTheme);}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus)applyChrome(chromeTheme);}

    /** The reading theme the web UI persisted, used for the native PDF surface. */
    String readingTheme(){try{return new JSONObject(prefs.getString("state","{}")).optString("theme","paper");}catch(Exception e){return "paper";}}
    boolean zenDefault(){try{return new JSONObject(prefs.getString("state","{}")).optBoolean("zen",true);}catch(Exception e){return true;}}

    // ---- native PDF surface --------------------------------------------------
    void showPdf(String id,String title){
        if(pdf!=null)return; // a PDF is already open; ignore a duplicate tap
        String theme=readingTheme();int bg=bgOf(theme),ink=inkOf(theme);
        try{
            pdfFd=ParcelFileDescriptor.open(new File(library,id+"/book.pdf"),ParcelFileDescriptor.MODE_READ_ONLY);
            pdf=new PdfRenderer(pdfFd);pdfId=id;
            if(pdf.getPageCount()<1)throw new IOException("这个 PDF 没有可显示的页面");
            pdfPage=Math.max(0,Math.min(prefs.getInt("pdf_"+id,0),pdf.getPageCount()-1));
            applyChrome(theme);
            pdfLayout=new LinearLayout(this);pdfLayout.setOrientation(1);pdfLayout.setBackgroundColor(bg);
            LinearLayout top=new LinearLayout(this);pdfTop=top;top.setGravity(Gravity.CENTER_VERTICAL);Button back=button("‹ 书架",theme,()->closePdf());top.addView(back);
            TextView t=new TextView(this);t.setText(title);t.setTextSize(17);t.setSingleLine(true);t.setTextColor(ink);t.setEllipsize(android.text.TextUtils.TruncateAt.END);top.addView(t,new LinearLayout.LayoutParams(0,dp(56),1));pdfLayout.addView(top);
            pdfCanvas=new PdfCanvas(this,matOf(theme));
            pdfLayout.addView(pdfCanvas,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout bottom=new LinearLayout(this);pdfBottom=bottom;bottom.setGravity(Gravity.CENTER);
            pdfPrev=button("上一页",theme,()->movePdf(-1));bottom.addView(pdfPrev);
            pageLabel=new TextView(this);pageLabel.setGravity(Gravity.CENTER);pageLabel.setTextSize(16);pageLabel.setTextColor(ink);pageLabel.setContentDescription("当前页码，点击可跳页");pageLabel.setOnClickListener(v->jumpPdf());bottom.addView(pageLabel,new LinearLayout.LayoutParams(0,dp(56),1));
            pdfNext=button("下一页",theme,()->movePdf(1));bottom.addView(pdfNext);
            pdfLayout.addView(bottom);
            if(zenDefault()){
                top.setVisibility(View.GONE);bottom.setVisibility(View.GONE);
                if(!prefs.getBoolean("zenHintedPdf",false)){prefs.edit().putBoolean("zenHintedPdf",true).apply();
                    Toast.makeText(this,"全屏阅读中 · 点击页面可显示上下菜单栏",Toast.LENGTH_LONG).show();}
            }
            pdfLayout.setPadding(0,safeTop,0,safeBottom);setContentView(pdfLayout);renderPdf();
        }catch(Throwable e){
            releasePdf();applyChrome("paper");setContentView(web);web.evaluateJavascript("exitPdf()",null);
            if(!isFinishing())new AlertDialog.Builder(this).setMessage("无法打开 PDF："+e.getMessage()).setPositiveButton("好",null).show();
        }
    }

    void togglePdfChrome(){if(pdfTop==null||pdfBottom==null)return;boolean show=pdfTop.getVisibility()!=View.VISIBLE;
        pdfTop.setVisibility(show?View.VISIBLE:View.GONE);pdfBottom.setVisibility(show?View.VISIBLE:View.GONE);}
    int dp(int x){return (int)(x*getResources().getDisplayMetrics().density);}
    Button button(String s,String theme,Runnable r){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);
        int ink=inkOf(theme);
        // Theme.Material.Light gives every Button a fixed light plate, which hides themed ink under 夜读.
        android.graphics.drawable.StateListDrawable bg=new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},new android.graphics.drawable.ColorDrawable(pressOf(theme)));
        bg.addState(new int[0],new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        b.setBackground(bg);b.setPadding(dp(18),dp(10),dp(18),dp(10));b.setMinimumHeight(dp(48));
        b.setTextColor(new android.content.res.ColorStateList(
            new int[][]{new int[]{-android.R.attr.state_enabled},new int[0]},
            new int[]{(ink&0x00FFFFFF)|0x4D000000,ink}));
        b.setOnClickListener(v->r.run());return b;}
    void movePdf(int delta){if(pdf==null||pdfLoading)return;int next=Math.max(0,Math.min(pdf.getPageCount()-1,pdfPage+delta));if(next!=pdfPage){pdfPage=next;renderPdf();}}
    void jumpPdf(){if(pdf==null||pdfLoading)return;EditText e=new EditText(this);e.setInputType(2);e.setHint("1 — "+pdf.getPageCount());new AlertDialog.Builder(this).setTitle("跳转页码").setView(e).setPositiveButton("前往",(d,w)->{if(pdf==null)return;try{pdfPage=Math.max(0,Math.min(pdf.getPageCount()-1,Integer.parseInt(e.getText().toString().trim())-1));renderPdf();}catch(Exception ignored){}}).setNegativeButton("取消",null).show();}

    void renderPdf(){
        final int page=pdfPage,version=++renderVersion;final PdfRenderer renderer=pdf;
        pdfLoading=true;pdfPrev.setEnabled(false);pdfNext.setEnabled(false);pageLabel.setText("正在加载第 "+(page+1)+" 页…");
        worker.execute(()->{
            try{
                Bitmap bitmap;
                synchronized(pdfLock){
                    if(renderer!=pdf)return;
                    PdfRenderer.Page p=renderer.openPage(page);
                    try{
                        float scale=Math.min(3f,2200f/Math.max(1,p.getWidth()));
                        int w=Math.max(1,(int)(p.getWidth()*scale)),h=Math.max(1,(int)(p.getHeight()*scale));
                        long budget=Math.max(2L*1024*1024,Runtime.getRuntime().maxMemory()/24); // pixels one page may use
                        if((long)w*h>budget){double k=Math.sqrt((double)budget/((long)w*h));w=Math.max(1,(int)(w*k));h=Math.max(1,(int)(h*k));}
                        try{bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);}
                        catch(OutOfMemoryError oom){bitmap=Bitmap.createBitmap(Math.max(1,w/2),Math.max(1,h/2),Bitmap.Config.RGB_565);}
                        bitmap.eraseColor(Color.WHITE);
                        p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }finally{p.close();}
                }
                final Bitmap done=bitmap;
                runOnUiThread(()->{
                    if(version!=renderVersion||pdf==null||pdfCanvas==null){done.recycle();return;}
                    pdfCanvas.setPage(done);pdfLoading=false;
                    pageLabel.setText((page+1)+" / "+pdf.getPageCount()+" · 跳页");
                    pdfPrev.setEnabled(page>0);pdfNext.setEnabled(page<pdf.getPageCount()-1);
                    prefs.edit().putInt("pdf_"+pdfId,page).apply();
                });
            }catch(Throwable e){
                final String msg=String.valueOf(e.getMessage());
                runOnUiThread(()->{
                    if(version!=renderVersion||pdf==null||pageLabel==null)return;
                    pdfLoading=false;pageLabel.setText("加载失败 · 点此跳页重试");
                    pdfPrev.setEnabled(page>0);pdfNext.setEnabled(page<pdf.getPageCount()-1);
                    Toast.makeText(MainActivity.this,"页面加载失败："+msg,Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    class PdfCanvas extends View {
        Bitmap bitmap;float zoom=1,offsetX=0,offsetY=0;boolean multi=false;float lastSpan=0;Paint paint=new Paint(3);ScaleGestureDetector scaler;GestureDetector gestures;
        PdfCanvas(Context c,int mat){super(c);setBackgroundColor(mat);setContentDescription("PDF 正文，左右滑动翻页，双指缩放，双击放大");
            scaler=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){zoom=Math.max(1,Math.min(4,zoom*d.getScaleFactor()));clamp();invalidate();return true;}});
            gestures=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(MotionEvent e){return true;}@Override public boolean onSingleTapConfirmed(MotionEvent e){togglePdfChrome();return true;}@Override public boolean onDoubleTap(MotionEvent e){zoom=zoom>1.1f?1:2.5f;offsetX=offsetY=0;clamp();invalidate();return true;}@Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){if(zoom>1.01f&&!multi){offsetX-=dx;offsetY-=dy;clamp();invalidate();}return true;}@Override public boolean onFling(MotionEvent a,MotionEvent b,float vx,float vy){if(a!=null&&!multi&&zoom<=1.01f&&Math.abs(b.getX()-a.getX())>dp(55)&&Math.abs(vx)>Math.abs(vy)*1.3f){movePdf(vx<0?1:-1);return true;}return false;}});
        }
        float fit(){return bitmap==null||getWidth()==0||getHeight()==0?1:Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());}
        void clamp(){if(bitmap==null)return;float sx=Math.max(0,(bitmap.getWidth()*fit()*zoom-getWidth())/2),sy=Math.max(0,(bitmap.getHeight()*fit()*zoom-getHeight())/2);offsetX=Math.max(-sx,Math.min(sx,offsetX));offsetY=Math.max(-sy,Math.min(sy,offsetY));}
        void setPage(Bitmap b){Bitmap old=bitmap;bitmap=b;zoom=1;offsetX=offsetY=0;invalidate();if(old!=null&&old!=b)old.recycle();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);if(bitmap!=null&&!bitmap.isRecycled()){float scale=fit()*zoom;c.save();c.translate((getWidth()-bitmap.getWidth()*scale)/2+offsetX,(getHeight()-bitmap.getHeight()*scale)/2+offsetY);c.scale(scale,scale);c.drawBitmap(bitmap,0,0,paint);c.restore();}}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){clamp();}
        @Override public boolean onTouchEvent(MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){multi=false;lastSpan=0;}
            if(e.getPointerCount()>1){multi=true;float span=(float)Math.hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&lastSpan>0){float old=zoom;zoom=Math.max(1,Math.min(4,zoom*span/lastSpan));float fx=(e.getX(0)+e.getX(1))/2-getWidth()/2f,fy=(e.getY(0)+e.getY(1))/2-getHeight()/2f;offsetX=fx-(fx-offsetX)*zoom/old;offsetY=fy-(fy-offsetY)*zoom/old;clamp();invalidate();}lastSpan=span;
            }else if(!multi)gestures.onTouchEvent(e);return true;
        }
        void release(){if(bitmap!=null){bitmap.recycle();bitmap=null;}}
    }

    /**
     * Hands the renderer to the worker for closing instead of closing it under a lock the
     * render task may still hold, so Back during a slow page render never blocks the UI thread.
     */
    void releasePdf(){
        renderVersion++;pdfLoading=false;
        final PdfRenderer r=pdf;final ParcelFileDescriptor fd=pdfFd;final String id=pdfId;final int page=pdfPage;
        pdf=null;pdfFd=null;
        if(id!=null&&r!=null)prefs.edit().putInt("pdf_"+id,page).apply();
        if(pdfCanvas!=null){pdfCanvas.release();pdfCanvas=null;}
        pdfLayout=null;pdfPrev=null;pdfNext=null;pageLabel=null;pdfTop=null;pdfBottom=null;
        if(r!=null||fd!=null)try{worker.execute(()->{synchronized(pdfLock){if(r!=null)r.close();if(fd!=null)try{fd.close();}catch(Exception ignored){}}});}catch(RejectedExecutionException ignored){}
    }

    void closePdf(){releasePdf();applyChrome("paper");setContentView(web);web.evaluateJavascript("exitPdf()",null);}

    /** Ask the page whether it consumed the gesture; if it had nothing left to close, leave the app. */
    void handleBack(){
        if(pdf!=null){closePdf();return;}
        web.evaluateJavascript("hostBack()",value->{if(!"true".equals(value))finish();});
    }
    @Override public void onBackPressed(){handleBack();}

    @Override protected void onPause(){
        super.onPause();
        web.evaluateJavascript("saveProgress()",null);
        if(pdf!=null&&pdfId!=null)prefs.edit().putInt("pdf_"+pdfId,pdfPage).commit();
    }

    @Override protected void onDestroy(){
        if(backCallback!=null&&Build.VERSION.SDK_INT>=33){try{BackCompat.unregister(this,backCallback);}catch(Exception ignored){}backCallback=null;}
        releasePdf();worker.shutdown();web.destroy();super.onDestroy();}
}
