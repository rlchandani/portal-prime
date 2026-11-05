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
import java.text.SimpleDateFormat;
import org.json.*;
public final class CalendarTimedValidityProbe {
 private static int deniedOpens;
 private static String ics(String start,String extra){return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:generated-timed-validation\r\nSUMMARY:Generated\r\n"+start+"\r\n"+extra+"END:VEVENT\r\nEND:VCALENDAR\r\n";}
 private static JSONArray event(long start,long end)throws JSONException{return new JSONArray().put(start).put(end);}
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("exact owned APK and owned DEX directory required");
  TimeZone originalZone=TimeZone.getDefault();Locale originalLocale=Locale.getDefault();
  JSONObject report;
  try{
   TimeZone.setDefault(TimeZone.getTimeZone("UTC"));Locale.setDefault(Locale.US);
   URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler(){
    protected URLConnection openConnection(URL u)throws IOException{deniedOpens++;throw new IOException("Generated calendar parser probe denied all URL opens");}
    protected URLConnection openConnection(URL u,java.net.Proxy proxy)throws IOException{return openConnection(u);}
   });
   DexClassLoader loader=new DexClassLoader(args[0],args[1],null,CalendarTimedValidityProbe.class.getClassLoader());
   Class<?> type=loader.loadClass("com.immortal.launcher.CalendarFeed");
   if(type.getClassLoader()!=loader)throw new IllegalStateException("Unexpected production class origin");
   Object instance=type.getField("INSTANCE").get(null);Method parse=null;int candidates=0;
   for(Method m:type.getDeclaredMethods()){
    Class<?>[] p=m.getParameterTypes();String name=m.getName();
    if((name.equals("parse")||name.startsWith("parse$"))&&p.length==3&&p[0]==String.class&&p[1]==long.class&&p[2]==int.class&&List.class.isAssignableFrom(m.getReturnType())){parse=m;candidates++;}
   }
   if(candidates!=1)throw new IllegalStateException("Expected one actual String/long/int CalendarFeed parser, got "+candidates);
   parse.setAccessible(true);
   String[] names={"invalid-start-Feb30","invalid-hour24","trailing-junk","malformed-EXDATE-retains-three","invalid-DTEND-defaults-to-start","valid-UTC-control","DST-gap-leniency-control","leap-second-leniency-control"};
   String[] inputs={
    ics("DTSTART:20260230T090000Z",""),
    ics("DTSTART:20260301T240000Z",""),
    ics("DTSTART:20260301T090000junkZ",""),
    ics("DTSTART:20260301T090000Z","RRULE:FREQ=DAILY;COUNT=3\r\nEXDATE:20260230T090000Z\r\n"),
    ics("DTSTART:20260228T090000Z","DTEND:20260230T090000Z\r\n"),
    ics("DTSTART:20260301T090000Z",""),
    ics("DTSTART;TZID=America/New_York:20260308T023000",""),
    ics("DTSTART:20161231T235960Z","")};
   long[] now={1769904000000L,1772323200000L,1772323200000L,1772323200000L,1769904000000L,1772323200000L,1772323200000L,1483142400000L};
   JSONArray[] expected={new JSONArray(),new JSONArray(),new JSONArray(),new JSONArray().put(event(1772355600000L,1772355600000L)).put(event(1772442000000L,1772442000000L)).put(event(1772528400000L,1772528400000L)),new JSONArray().put(event(1772269200000L,1772269200000L)),new JSONArray().put(event(1772355600000L,1772355600000L)),new JSONArray().put(event(1772955000000L,1772955000000L)),new JSONArray().put(event(1483228800000L,1483228800000L))};
   JSONArray cases=new JSONArray();int passed=0;
   for(int i=0;i<names.length;i++){
    int before=deniedOpens;JSONArray actual=null;String failure=null;
    try{Object result=parse.invoke(instance,inputs[i],now[i],45);actual=new JSONArray();for(Object item:(List<?>)result){Class<?> c=item.getClass();if(c.getClassLoader()!=loader)throw new IllegalStateException("Unexpected Event class origin");actual.put(new JSONArray().put(c.getMethod("getStartMillis").invoke(item)).put(c.getMethod("getEndMillis").invoke(item)));}}
    catch(InvocationTargetException e){failure=e.getCause().getClass().getName();}
    boolean literal=failure==null&&actual!=null&&actual.toString().equals(expected[i].toString());boolean noUrl=deniedOpens==before;boolean ok=literal&&noUrl;if(ok)passed++;
    cases.put(new JSONObject().put("name",names[i]).put("generatedICS",inputs[i]).put("nowMillis",now[i]).put("horizonDays",45).put("expected",expected[i]).put("expectedCount",expected[i].length()).put("actual",actual==null?JSONObject.NULL:actual).put("failure",failure==null?JSONObject.NULL:failure).put("literalPassed",literal).put("urlOpens",deniedOpens-before).put("passed",ok));
   }
   report=new JSONObject().put("caseCount",8).put("passed",passed).put("sourceClassLoaderVerified",true).put("actualParseMethod",parse.getName()).put("api",android.os.Build.VERSION.SDK_INT).put("fixtureZone",TimeZone.getDefault().getID()).put("fixtureLocale",Locale.getDefault().toString()).put("frameworkSDFClassLoader",String.valueOf(SimpleDateFormat.class.getClassLoader())).put("deniedUrlOpens",deniedOpens).put("actualNetworkRequests",0).put("scope","Actual packaged CalendarFeed.parse with generated ICS; framework timed parsing; no Context/fetch/provider/preferences").put("cases",cases);
  }finally{TimeZone.setDefault(originalZone);Locale.setDefault(originalLocale);}
  report.put("processZoneRestored",TimeZone.getDefault().equals(originalZone)).put("processLocaleRestored",Locale.getDefault().equals(originalLocale));
  System.out.println(report.toString());
 }
}
