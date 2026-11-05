/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import dalvik.system.DexClassLoader;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
public final class DailyContentNegativeDayProbe {
 private static int deniedUrlOpens;
 private static Method exact(Class<?> type,String name,Class<?> result)throws Exception{
  Method selected=null;int count=0;
  for(Method m:type.getDeclaredMethods()){Class<?>[] p=m.getParameterTypes();if(m.getName().equals(name)&&Modifier.isPublic(m.getModifiers())&&p.length==1&&p[0]==long.class&&m.getReturnType()==result){selected=m;count++;}}
  if(count!=1)throw new IllegalStateException("Expected exactly one public "+name+"(long), got "+count);return selected;
 }
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("exact owned debug APK and owned DEX directory required");
  TimeZone originalZone=TimeZone.getDefault();Locale originalLocale=Locale.getDefault();JSONObject report;
  try{
   Locale.setDefault(Locale.US);TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
   URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler(){protected URLConnection openConnection(URL u)throws IOException{deniedUrlOpens++;throw new IOException("Generated daily content probe denied all URL opens");}protected URLConnection openConnection(URL u,java.net.Proxy proxy)throws IOException{return openConnection(u);}});
   DexClassLoader loader=new DexClassLoader(args[0],args[1],null,DailyContentNegativeDayProbe.class.getClassLoader());
   Class<?> type=loader.loadClass("com.immortal.launcher.DailyContent"),quote=loader.loadClass("com.immortal.launcher.DailyContent$Quote"),word=loader.loadClass("com.immortal.launcher.DailyContent$Word"),trivia=loader.loadClass("com.immortal.launcher.DailyContent$Trivia");
   if(type.getClassLoader()!=loader||quote.getClassLoader()!=loader||word.getClassLoader()!=loader||trivia.getClassLoader()!=loader)throw new IllegalStateException("Unexpected production class origin");
   Object instance=type.getField("INSTANCE").get(null);
   Method day=exact(type,"epochDay",long.class),quoteOf=exact(type,"quoteOfDay",quote),wordOf=exact(type,"wordOfDay",word),triviaOf=exact(type,"triviaOfDay",trivia);
   Method text=quote.getMethod("getText"),wordText=word.getMethod("getWord"),answer=trivia.getMethod("getAnswer");
   String[] names={"UTC-one-millisecond-before-epoch","UTC-one-millisecond-before-minus-one-day","UTC-negative-day-last-quote","UTC-negative-day-last-word","UTC-negative-day-last-trivia","UTC-epoch-day-zero-control","UTC-first-quote-control","Los-Angeles-local-midnight-control"};
   long[] millis={-1L,-86400001L,-86400000L,-86400000L,-86400000L,0L,0L,28800000L};
   Method[] entries={day,day,quoteOf,wordOf,triviaOf,day,quoteOf,day},getters={null,null,text,wordText,answer,null,text,null};
   Object[] expected={-1L,-2L,"The harder you work for something, the greater you'll feel when you achieve it.","zenith","Russia",0L,"The best way to predict the future is to invent it.",0L};
   JSONArray cases=new JSONArray();int passed=0;
   for(int i=0;i<names.length;i++){
    TimeZone.setDefault(TimeZone.getTimeZone(i==7?"America/Los_Angeles":"UTC"));int before=deniedUrlOpens;Object actual=null;String failure=null;boolean returnedClassVerified=getters[i]==null;
    try{Object value=entries[i].invoke(instance,millis[i]);if(getters[i]!=null){if(value==null||value.getClass().getClassLoader()!=loader||!getters[i].getDeclaringClass().isInstance(value))throw new IllegalStateException("Unexpected returned content class origin");returnedClassVerified=true;actual=getters[i].invoke(value);}else actual=value;}
    catch(InvocationTargetException e){failure=e.getCause().getClass().getName();}
    boolean literal=failure==null&&expected[i].equals(actual),noUrl=deniedUrlOpens==before,ok=literal&&noUrl;if(ok)passed++;
    cases.put(new JSONObject().put("name",names[i]).put("method",entries[i].getName()).put("getter",getters[i]==null?JSONObject.NULL:getters[i].getName()).put("nowMillis",millis[i]).put("zone",TimeZone.getDefault().getID()).put("expected",expected[i]).put("actual",actual==null?JSONObject.NULL:actual).put("failure",failure==null?JSONObject.NULL:failure).put("returnedClassOriginVerified",returnedClassVerified).put("literalPassed",literal).put("urlOpens",deniedUrlOpens-before).put("passed",ok));
   }
   report=new JSONObject().put("caseCount",8).put("passed",passed).put("sourceClassLoaderVerified",true).put("api",android.os.Build.VERSION.SDK_INT).put("deniedUrlOpens",deniedUrlOpens).put("actualNetworkRequests",0).put("scope","Actual APK DailyContent public methods and value getters; generated millis/process zones; no HTTP/Context/preferences/UI/provider").put("cases",cases);
  }finally{TimeZone.setDefault(originalZone);Locale.setDefault(originalLocale);}
  report.put("processZoneRestored",TimeZone.getDefault().equals(originalZone)).put("processLocaleRestored",Locale.getDefault().equals(originalLocale));System.out.println(report.toString());
 }
}
