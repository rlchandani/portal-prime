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
public final class CalendarUntilValidityProbe {
 private static int deniedUrlOpens;
 // Explicit valid Gregorian UTC instants only; not an UNTIL or recurrence parser.
 private static long utc(int year,int month,int day,int hour){GregorianCalendar c=new GregorianCalendar(TimeZone.getTimeZone("UTC"),Locale.US);c.setLenient(false);c.clear();c.set(year,month-1,day,hour,0,0);return c.getTimeInMillis();}
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("exact owned debug APK and owned DEX directory required");
  TimeZone originalZone=TimeZone.getDefault();Locale originalLocale=Locale.getDefault();JSONObject report;
  try{
   TimeZone.setDefault(TimeZone.getTimeZone("UTC"));Locale.setDefault(Locale.US);
   URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler(){
    protected URLConnection openConnection(URL u)throws IOException{deniedUrlOpens++;throw new IOException("Generated recurrence probe denied all URL opens");}
    protected URLConnection openConnection(URL u,java.net.Proxy proxy)throws IOException{return openConnection(u);}
   });
   DexClassLoader loader=new DexClassLoader(args[0],args[1],null,CalendarUntilValidityProbe.class.getClassLoader());
   Class<?> type=loader.loadClass("com.immortal.launcher.CalendarFeed");if(type.getClassLoader()!=loader)throw new IllegalStateException("Unexpected production class origin");
   Object instance=type.getField("INSTANCE").get(null);Method expand=null;int candidates=0;
   for(Method m:type.getDeclaredMethods()){
    Class<?>[] p=m.getParameterTypes();String name=m.getName();
    if((name.equals("expandRecurrence")||name.startsWith("expandRecurrence$"))&&p.length==6&&p[0]==long.class&&p[1]==String.class&&p[2]==List.class&&p[3]==long.class&&p[4]==Long.class&&p[5]==TimeZone.class&&List.class.isAssignableFrom(m.getReturnType())){expand=m;candidates++;}
   }
   if(candidates!=1)throw new IllegalStateException("Expected exactly one actual six-argument recurrence method, got "+candidates);expand.setAccessible(true);
   String[] names={"impossible-UNTIL-date-fallback","invalid-UNTIL-hour-fallback","trailing-UNTIL-time-junk-fallback","short-UNTIL-time-fallback","unreadable-UNTIL-fallback","valid-date-inclusive-control","valid-UTC-time-inclusive-control","valid-local-time-control"};
   String[] rules={"FREQ=DAILY;UNTIL=20330230","FREQ=DAILY;UNTIL=20330103T240000Z","FREQ=DAILY;UNTIL=20330103T090000Zjunk","FREQ=DAILY;UNTIL=20330103T0900","FREQ=DAILY;UNTIL=bad","FREQ=DAILY;UNTIL=20330103","FREQ=DAILY;UNTIL=20330103T090000Z","FREQ=DAILY;UNTIL=20330103T090000"};
   long start=utc(2033,1,1,9),horizon=utc(2033,1,11,9),localStart=utc(2033,1,1,17);
   JSONArray single=new JSONArray().put(start),three=new JSONArray().put(start).put(utc(2033,1,2,9)).put(utc(2033,1,3,9)),localThree=new JSONArray().put(localStart).put(utc(2033,1,2,17)).put(utc(2033,1,3,17));
   JSONArray cases=new JSONArray();int passed=0;
   for(int i=0;i<names.length;i++){
    long caseStart=i==7?localStart:start;TimeZone zone=TimeZone.getTimeZone(i==7?"America/Los_Angeles":"UTC");JSONArray expected=i<5?single:i==7?localThree:three;
    int before=deniedUrlOpens;JSONArray actual=null;String failure=null;
    try{Object result=expand.invoke(instance,caseStart,rules[i],Collections.<Long>emptyList(),horizon,null,zone);actual=new JSONArray();for(Object occurrence:(List<?>)result){if(!(occurrence instanceof Long))throw new IllegalStateException("Unexpected recurrence result type");actual.put(occurrence);}}
    catch(InvocationTargetException e){failure=e.getCause().getClass().getName();}
    boolean literal=failure==null&&actual!=null&&actual.toString().equals(expected.toString());boolean noUrl=deniedUrlOpens==before;boolean ok=literal&&noUrl;if(ok)passed++;
    cases.put(new JSONObject().put("name",names[i]).put("rrule",rules[i]).put("startMillis",caseStart).put("horizonEndMillis",horizon).put("zone",zone.getID()).put("exdates",new JSONArray()).put("notBefore",JSONObject.NULL).put("expected",expected).put("actual",actual==null?JSONObject.NULL:actual).put("failure",failure==null?JSONObject.NULL:failure).put("literalPassed",literal).put("urlOpens",deniedUrlOpens-before).put("passed",ok));
   }
   report=new JSONObject().put("caseCount",8).put("passed",passed).put("sourceClassLoaderVerified",true).put("actualExpandMethod",expand.getName()).put("api",android.os.Build.VERSION.SDK_INT).put("deniedUrlOpens",deniedUrlOpens).put("actualNetworkRequests",0).put("scope","Actual APK CalendarFeed.expandRecurrence; malformed present UNTIL single-start fallback only; finite generated rules and literal instants").put("cases",cases);
  }finally{TimeZone.setDefault(originalZone);Locale.setDefault(originalLocale);}
  report.put("processZoneRestored",TimeZone.getDefault().equals(originalZone)).put("processLocaleRestored",Locale.getDefault().equals(originalLocale));System.out.println(report.toString());
 }
}
