package com.pekinlcc.reader;
// Plain-JDK test for Html.java (no Android needed):
//   javac -d build/t app/src/com/pekinlcc/reader/Html.java tests/HtmlTest.java && java -cp build/t com.pekinlcc.reader.HtmlTest
import java.nio.charset.*;
public class HtmlTest {
 static int fails=0;
 static void eq(String got,String want,String label){boolean ok=got.equals(want);if(!ok)fails++;System.out.println((ok?"PASS ":"FAIL ")+label+(ok?"":"\n  got:  "+got+"\n  want: "+want));}
 static String n(byte[] b){return new String(Html.normalize(b),StandardCharsets.UTF_8);}
 static String n(String s){return n(s.getBytes(StandardCharsets.UTF_8));}
 public static void main(String[] a) throws Exception {
  eq(n("<p>a<a id=\"x\"/>b</p>"),"<p>a<a id=\"x\"></a>b</p>","anchor expanded");
  eq(n("<br/><br /><img src=\"a.png\"/><hr/>"),"<br/><br /><img src=\"a.png\"/><hr/>","void untouched");
  eq(n("<head><title/></head>"),"<head><title></title></head>","title expanded");
  eq(n("<a title=\"a/>b\" id='q'/>x"),"<a title=\"a/>b\" id='q'></a>x","quoted > and / in values");
  eq(n("<svg><path d=\"M0 0\"/><image href=\"i.png\"/></svg>"),"<svg><path d=\"M0 0\"></path><image href=\"i.png\"/></svg>","svg children");
  eq(n("a<br></br>b<BR></BR>"),"a<br>b<BR>","</br> dropped");
  eq(n("<p class=\"x\">t</p><a href=x/>"),"<p class=\"x\">t</p><a href=x/>","non-matching untouched");
  eq(n("<div\n  class=\"c\"\n/>"),"<div\n  class=\"c\"></div>","multiline tag");
  eq(n("<?xml version=\"1.0\"?><!DOCTYPE html><html/>"),"<?xml version=\"1.0\"?><!DOCTYPE html><html></html>","decl/doctype safe");
  eq(n("<epub:switch id=\"s\"/>"),"<epub:switch id=\"s\"></epub:switch>","prefixed");
  String zh="<?xml version=\"1.0\" encoding=\"gb2312\"?><html><body><p>天地玄黄，宇宙洪荒。</p></body></html>";
  eq(n(zh.getBytes("GBK")),zh,"declared gb2312 decoded");
  String stale="<meta http-equiv=\"Content-Type\" content=\"text/html; charset=gb2312\"/><p>天地玄黄</p>";
  eq(n(stale),stale.replace("\"/>","\"/>"),"valid utf-8 beats stale gb2312");
  String nodecl="<html><body><p>天地玄黄，宇宙洪荒，日月盈昃，辰宿列张。</p></body></html>";
  eq(n(nodecl.getBytes("GBK")),nodecl,"undeclared gbk -> GB18030");
  byte[] broken=(nodecl).getBytes(StandardCharsets.UTF_8);byte[] b2=new byte[broken.length+1];System.arraycopy(broken,0,b2,0,20);b2[20]=(byte)0xE5;System.arraycopy(broken,20,b2,21,broken.length-20);
  String got=n(b2);eq(String.valueOf(got.contains("宇宙洪荒")&&got.contains("天")),"true","one broken byte stays utf-8");
  byte[] u16=("﻿"+nodecl).getBytes("UTF-16LE");eq(n(u16),nodecl,"utf-16le bom");
  eq(n(("﻿"+nodecl).getBytes(StandardCharsets.UTF_8)),nodecl,"utf-8 bom stripped");
  StringBuilder big=new StringBuilder("<html><body>");for(int i=0;i<40000;i++)big.append("<p class=\"t\" id=\"p").append(i).append("\">天地玄黄，宇宙洪荒<a id=\"a").append(i).append("\"/>，日月盈昃<br/>辰宿列张</p>\n");big.append("</body></html>");
  byte[] bb=big.toString().getBytes(StandardCharsets.UTF_8);long t=System.nanoTime();String out=n(bb);long ms=(System.nanoTime()-t)/1000000;
  System.out.println("big: "+bb.length/1024+" KiB in "+ms+" ms; expanded="+(out.split("></a>").length-1));
  if(ms>2000)fails++;
  System.out.println(fails==0?"ALL PASS":fails+" FAILED");System.exit(fails==0?0:1);
 }}
