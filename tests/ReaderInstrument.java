package com.pekinlcc.reader;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.graphics.*;import java.io.*;
public class ReaderInstrument extends Instrumentation {
 boolean resumeOnly=false;String[] pdfIds=new String[0];MainActivity a;StringBuilder report=new StringBuilder();
 // Book ids are content hashes of your own files, so they are passed in: -e ids <id1>,<id2>
 @Override public void onCreate(Bundle b){super.onCreate(b);resumeOnly="true".equals(b.getString("resume"));String ids=b.getString("ids");if(ids!=null&&!ids.trim().isEmpty())pdfIds=ids.trim().split(",");start();}
 void check(boolean ok,String label){if(!ok)throw new AssertionError(label);report.append("PASS ").append(label).append('\n');}
 void ui(Runnable r){runOnMainSync(r);waitForIdleSync();}
 void ready()throws Exception{for(int i=0;i<200;i++){Thread.sleep(50);if(!a.pdfLoading&&a.pdfCanvas!=null&&a.pdfCanvas.bitmap!=null){waitForIdleSync();return;}}throw new Exception("PDF rendering timed out");}
 void swipe(float x1,float y1,float x2,float y2){long t=SystemClock.uptimeMillis();sendPointerSync(MotionEvent.obtain(t,t,0,x1,y1,0));for(int i=1;i<=8;i++){long at=t+i*25;sendPointerSync(MotionEvent.obtain(t,at,2,x1+(x2-x1)*i/8,y1+(y2-y1)*i/8,0));}sendPointerSync(MotionEvent.obtain(t,t+225,1,x2,y2,0));waitForIdleSync();}
 void tap(float x,float y){long t=SystemClock.uptimeMillis();sendPointerSync(MotionEvent.obtain(t,t,0,x,y,0));sendPointerSync(MotionEvent.obtain(t,t+30,1,x,y,0));waitForIdleSync();}
 void pinch()throws Exception{
  long t=SystemClock.uptimeMillis();MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[2];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[2];
  for(int i=0;i<2;i++){props[i]=new MotionEvent.PointerProperties();props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].y=1100;coords[i].pressure=1;coords[i].size=1;}
  coords[0].x=700;coords[1].x=900;
  sendPointerSync(MotionEvent.obtain(t,t,0,1,props,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0));
  sendPointerSync(MotionEvent.obtain(t,t+30,5|(1<<8),2,props,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0));
  for(int i=1;i<=12;i++){Thread.sleep(30);coords[0].x=700-i*15;coords[1].x=900+i*15;sendPointerSync(MotionEvent.obtain(t,t+30+i*25,2,2,props,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0));}
  sendPointerSync(MotionEvent.obtain(t,t+360,6|(1<<8),2,props,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0));sendPointerSync(MotionEvent.obtain(t,t+390,1,1,props,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0));waitForIdleSync();
 }
 @Override public void onStart(){Bundle result=new Bundle();try{
  Intent intent=new Intent(getTargetContext(),MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);a=(MainActivity)startActivitySync(intent);Thread.sleep(1000);
  if(pdfIds.length==0)throw new IllegalArgumentException("pass PDF book ids with -e ids <id1>,<id2>");
  for(String id:pdfIds){
   ui(()->a.showPdf(id,id));ready();if(resumeOnly){check(a.pdfPage==7,"PDF page restored after process restart "+id);ui(()->{a.pdfPage=0;a.renderPdf();});ready();ui(()->a.closePdf());continue;}int total=a.pdf.getPageCount();report.append("PDF "+id+" pages="+total+"\n");
   ui(()->{a.pdfPage=0;a.renderPdf();});ready();check(!a.pdfPrev.isEnabled(),"first boundary disabled");
   Bitmap first=a.pdfCanvas.bitmap;int firstPixel=first.getPixel(first.getWidth()/2,first.getHeight()/2);
   for(int i=1;i<=4;i++){ui(()->a.pdfNext.performClick());ready();check(a.pdfPage==i,"next page "+i);check(a.pdfCanvas.bitmap!=first,"new rendered bitmap");}
   for(int i=3;i>=0;i--){ui(()->a.pdfPrev.performClick());ready();check(a.pdfPage==i,"previous page "+i);}
   swipe(1250,1200,450,1200);ready();check(a.pdfPage==1,"left swipe advances");swipe(450,1200,1250,1200);ready();check(a.pdfPage==0,"right swipe reverses");
   tap(800,1100);Thread.sleep(80);tap(800,1100);Thread.sleep(300);check(a.pdfCanvas.zoom>2,"double tap zoom");float before=a.pdfCanvas.offsetX;swipe(800,1100,1150,1250);check(a.pdfCanvas.offsetX!=before,"zoomed pan");check(a.pdfPage==0,"pan does not turn page");
   tap(800,1100);Thread.sleep(80);tap(800,1100);Thread.sleep(300);check(a.pdfCanvas.zoom==1,"double tap resets zoom");pinch();check(a.pdfCanvas.zoom>1.5,"two finger pinch zoom actual="+a.pdfCanvas.zoom);
   ui(()->{a.pdfPage=total-1;a.renderPdf();});ready();check(!a.pdfNext.isEnabled(),"last boundary disabled");check(a.pdfCanvas.zoom==1,"new page resets viewport");
   ui(()->{a.pdfPage=7;a.renderPdf();});ready();ui(()->a.closePdf());ui(()->a.showPdf(id,id));ready();check(a.pdfPage==7,"close and reopen remembers page");
   ui(()->a.closePdf());
  }
  result.putString("stream",report.toString());finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream",report+"FAIL "+e);finish(Activity.RESULT_CANCELED,result);}}
}
