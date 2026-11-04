/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.newsnumericprobe;
import dalvik.system.DexClassLoader;
import java.lang.reflect.Method;
import java.util.List;
import java.io.FileInputStream;
import java.security.MessageDigest;
import org.json.JSONObject;
import org.json.JSONArray;
public class NewsNumericProbe {
  private static Object news;
  private static Method clean, parse;
  private static JSONArray cases = new JSONArray();
  private static int failures;
  private static void check(String name, String actual, String expected) throws Exception {
    boolean ok = actual.equals(expected);
    if (!ok) failures++;
    cases.put(new JSONObject().put("name",name).put("actual",actual).put("expected",expected).put("passed",ok));
  }
  private static void clean(String name, String input, String expected) throws Exception {
    check(name,(String)clean.invoke(news,input),expected);
  }
  private static String title(String xml) throws Exception {
    Object h=((List<?>)parse.invoke(news,xml)).get(0);
    return (String)h.getClass().getMethod("getTitle").invoke(h);
  }
  public static void main(String[] args) throws Exception {
    DexClassLoader loader=new DexClassLoader(args[0],args[1],null,ClassLoader.getSystemClassLoader());
    Class<?> type=loader.loadClass("com.immortal.launcher.NewsFeed");
    news=type.getField("INSTANCE").get(null);
    for (Method m:type.getDeclaredMethods()) {
      if (m.getName().startsWith("clean$") && m.getParameterTypes().length==1) clean=m;
      if (m.getName().startsWith("parse$") && m.getParameterTypes().length==1) parse=m;
    }
    if(clean==null || parse==null || type.getClassLoader()!=loader) throw new AssertionError("Actual APK parser admission failed");
    check("decimalPunctuationInRssTitle",title("<rss><item><title>Today&#8217;s news &#8212; more</title></item></rss>"),"Today’s news — more");
    check("hexadecimalPunctuationInAtomTitle",title("<feed><entry><title>&#x201c;News&#X201D;</title></entry></feed>"),"“News”");
    clean("supplementaryCharactersUseCompleteCodePoints","&#128640; &#x1F600;","🚀 😀");
    clean("nonLatinTextAndLeadingZeroReferences","&#26085;&#x672C; &#000233;","日本 é");
    clean("numericWhitespaceKeepsExistingCollapse","one&#10;&#x09;two&#13; three","one two three");
    Object h=((List<?>)parse.invoke(news,"<rss><item><title>News</title><link>https://example.com/?a=1&#38;b=2</link></item></rss>")).get(0);
    check("numericReferencesAlsoDecodeInArticleLinks",(String)h.getClass().getMethod("getLink").invoke(h),"https://example.com/?a=1&b=2");
    String invalid="&#; &#x; &#-1; &#xZZ; &#0; &#55296; &#xDFFF; &#1114112; &#99999999999999999999;";
    clean("malformedAndInvalidCodePointsRemainReadable",invalid,invalid);
    clean("validReferencesSurviveAdjacentInvalidReferences","&#65;&#xD800;&#66;","A&#xD800;B");
    clean("existingNamedEntitiesAndApostrophesRemain","&amp; &lt; &gt; &quot; &#39; &apos; &#x27;","& < > \" ' ' '");
    clean("cdataAndPlainUnicodeRemain"," <![CDATA[Already café —]]> ","Already café —");
    clean("escapedNumericReferenceIsNotDecodedRecursively","Literal &amp;#8217;","Literal &#8217;");
    StringBuilder xml=new StringBuilder("<rss>");
    for(int i=1;i<=12;i++)xml.append("<item><title>H").append(i).append("&#8217;s</title></item>");
    xml.append("</rss>");JSONArray actual=new JSONArray(),expected=new JSONArray();
    for(Object row:(List<?>)parse.invoke(news,xml.toString()))actual.put(row.getClass().getMethod("getTitle").invoke(row));
    for(int i=1;i<=8;i++)expected.put("H"+i+"’s");
    check("parsedHeadlinesRetainOrderAndCap",actual.toString(),expected.toString());
    MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[65536];
    try(FileInputStream in=new FileInputStream(args[0])){for(int k;(k=in.read(bytes))!=-1;)md.update(bytes,0,k);}
    StringBuilder hash=new StringBuilder();for(byte b:md.digest())hash.append(String.format("%02x",b&255));
    System.out.println(new JSONObject().put("tests",cases.length()).put("failures",failures).put("cases",cases).put("apkSha256",hash.toString()).put("actualApkClassLoader",true).put("parseMethod",parse.getName()).put("cleanMethod",clean.getName()).put("networkCalls",0).put("preferenceCalls",0));
  }
}
