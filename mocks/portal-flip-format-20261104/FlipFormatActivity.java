/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.flipformatfixture;
import android.app.Activity;
import android.content.*;
import android.os.*;
import dalvik.system.DexClassLoader;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/** Actual detached Android constructor and formatter consumers; no formatter or retained-state seeding. */
public final class FlipFormatActivity extends Activity {
 private final Handler owner=new Handler(Looper.getMainLooper());private final JSONArray cases=new JSONArray();
 private File source;private Class<?> specType,clockType;private Object companion;private Method fromJson;private Constructor<?> constructor;
 private TimeZone originalZone;private Locale originalLocale,originalFormat,originalDisplay;
 private boolean closed,origin,defaultsRestored;private int failures,setupFailures,constructed,disposed;
 private final OwnedContext context=new OwnedContext(this);
 public void onCreate(Bundle b){super.onCreate(b);owner.postDelayed(()->finishReport("owner_timeout"),30000);owner.post(this::probe);}
 private void probe(){originalZone=TimeZone.getDefault();originalLocale=Locale.getDefault();originalFormat=Locale.getDefault(Locale.Category.FORMAT);originalDisplay=Locale.getDefault(Locale.Category.DISPLAY);String error=null;try{
  source=new File(getCacheDir(),"source.apk");try(InputStream in=getAssets().open("source.apk");OutputStream out=new FileOutputStream(source)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);}
  DexClassLoader loader=new DexClassLoader(source.getAbsolutePath(),getCodeCacheDir().getAbsolutePath(),null,getClassLoader().getParent());specType=loader.loadClass("com.immortal.launcher.ClockSpec");clockType=loader.loadClass("com.immortal.launcher.ui.clock.SplitFlapClockFaceView");check(specType.getClassLoader()==loader&&clockType.getClassLoader()==loader,"Exact embedded class origin");origin=true;companion=specType.getField("Companion").get(null);fromJson=companion.getClass().getMethod("fromJson",JSONObject.class);constructor=clockType.getConstructor(Context.class,specType);
  runCase("twelveHourAfternoon","12h","2030-01-01 13:05","0105");runCase("twelveHourMidnight","12h","2030-01-01 00:05","1205");runCase("twelveHourNoon","12h","2030-01-01 12:05","1205");runCase("twelveHourLateEvening","12h","2030-01-01 23:59","1159");runCase("twentyFourHourAfternoon","24h","2030-01-01 13:05","1305");runCase("twentyFourHourMidnight","24h","2030-01-01 00:05","0005");runCase("twentyFourHourNoon","24h","2030-01-01 12:05","1205");runCase("twentyFourHourLateEvening","24h","2030-01-01 23:59","2359");
 }catch(Throwable t){error="setup:"+describe(t);}finally{restore(originalZone,originalLocale,originalFormat,originalDisplay);defaultsRestored=restored(originalZone,originalLocale,originalFormat,originalDisplay);}finishReport(error);}
 private void runCase(String name,String format,String literalDate,String expected)throws Exception{
  TimeZone zone=TimeZone.getDefault();Locale locale=Locale.getDefault(),fl=Locale.getDefault(Locale.Category.FORMAT),dl=Locale.getDefault(Locale.Category.DISPLAY);Object clock=null;boolean initialized=false,retired=false;List<String> errors=new ArrayList<>();String setup=null,actual=null,reveal=null,pending=null,tick=null;try{
   TimeZone.setDefault(TimeZone.getTimeZone("UTC"));Locale.setDefault(Locale.US);Locale.setDefault(Locale.Category.FORMAT,Locale.US);Locale.setDefault(Locale.Category.DISPLAY,Locale.US);
   SimpleDateFormat input=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);input.setTimeZone(TimeZone.getTimeZone("UTC"));input.setLenient(false);Date date=input.parse(literalDate);
   Object spec=fromJson.invoke(companion,new JSONObject().put("format",format));clock=constructor.newInstance(context,spec);constructed++;SimpleDateFormat formatter=(SimpleDateFormat)field(clock,"timeFmt");initialized=true;
   actual=formatter.format(date);require(errors,expected.equals(actual),"Initialized formatter literal expected="+expected+" actual="+actual);
   clockType.getMethod("setHostActive",boolean.class).invoke(clock,false);clockType.getMethod("refreshForReveal",Date.class,boolean.class).invoke(clock,date,false);reveal=new String((char[])field(clock,"displayedDigits"));pending=new String((char[])field(clock,"pendingDigits"));require(errors,expected.equals(reveal)&&expected.equals(pending),"Actual refreshForReveal literal digits");require(errors,Boolean.FALSE.equals(field(clock,"hostActive")),"Reveal retains inactive host");
   clockType.getMethod("setHostActive",boolean.class).invoke(clock,true);clockType.getMethod("update",Date.class,boolean.class).invoke(clock,date,false);tick=new String((char[])field(clock,"displayedDigits"));require(errors,expected.equals(tick)&&expected.equals(new String((char[])field(clock,"pendingDigits"))),"First resumed update literal digits");require(errors,allNull((Object[])field(clock,"animators")),"Reveal/first tick creates no animators");
  }catch(Throwable t){if(!initialized){setup=describe(t);setupFailures++;}else errors.add("consumer exception:"+describe(t));}finally{
   if(clock!=null)try{clockType.getMethod("dispose").invoke(clock);retired=Boolean.TRUE.equals(field(clock,"disposed"))&&Boolean.FALSE.equals(field(clock,"hostActive"))&&allNull((Object[])field(clock,"animators"));if(retired)disposed++;}catch(Throwable t){errors.add("dispose:"+describe(t));}
   restore(zone,locale,fl,dl);
  }
  boolean clean=restored(zone,locale,fl,dl);require(errors,clean,"Process defaults restored");if(clock!=null)require(errors,retired,"Constructed clock disposed");require(errors,context.unexpected==0,"No preference/resolver/service/activity operation");boolean pass=setup==null&&errors.isEmpty();if(!pass)failures++;
  cases.put(new JSONObject().put("name",name).put("passed",pass).put("format",format).put("input",literalDate+" UTC").put("expected",expected).put("actual",actual==null?JSONObject.NULL:actual).put("revealDigits",reveal==null?JSONObject.NULL:reveal).put("revealPending",pending==null?JSONObject.NULL:pending).put("firstResumedTickDigits",tick==null?JSONObject.NULL:tick).put("setupError",setup==null?JSONObject.NULL:setup).put("errors",new JSONArray(errors)).put("actualConstructor",clock!=null).put("formatterReadOnly",initialized).put("disposed",retired).put("defaultsRestored",clean).put("unexpectedContextOperations",context.unexpected));
 }
 private static final class OwnedContext extends ContextWrapper {
  int unexpected;OwnedContext(Context activity){super(activity);}private AssertionError denied(String op){unexpected++;return new AssertionError("No generated clock context operation admitted: "+op);}
  @Override public Context getApplicationContext(){return this;}
  @Override public SharedPreferences getSharedPreferences(String n,int m){throw denied("SharedPreferences");}
  @Override public ContentResolver getContentResolver(){throw denied("ContentResolver");}
  @Override public Object getSystemService(String n){throw denied("systemService:"+n);}
  @Override public String getSystemServiceName(Class<?> type){throw denied("typedSystemService:"+type.getName());}
  @Override public void startActivity(Intent i){throw denied("startActivity");}
  @Override public void startActivity(Intent i,Bundle b){throw denied("startActivityBundle");}
 }
 private Object field(Object obj,String name)throws Exception{Field f=clockType.getDeclaredField(name);f.setAccessible(true);return f.get(obj);}
 private static boolean allNull(Object[] values){for(Object o:values)if(o!=null)return false;return true;}
 private static void require(List<String> errors,boolean ok,String message){if(!ok)errors.add(message);}private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 private static String describe(Throwable t){while(t instanceof InvocationTargetException&&t.getCause()!=null)t=t.getCause();return t.getClass().getName()+":"+t.getMessage();}
 private static void restore(TimeZone z,Locale l,Locale f,Locale d){TimeZone.setDefault(z);Locale.setDefault(l);Locale.setDefault(Locale.Category.FORMAT,f);Locale.setDefault(Locale.Category.DISPLAY,d);}private static boolean restored(TimeZone z,Locale l,Locale f,Locale d){return TimeZone.getDefault().equals(z)&&Locale.getDefault().equals(l)&&Locale.getDefault(Locale.Category.FORMAT).equals(f)&&Locale.getDefault(Locale.Category.DISPLAY).equals(d);}
 private static String digest(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
 private synchronized void finishReport(String error){if(closed)return;closed=true;owner.removeCallbacksAndMessages(null);try{JSONObject r=new JSONObject().put("ok",error==null&&cases.length()==8&&failures==0&&setupFailures==0&&constructed==8&&disposed==8&&defaultsRestored&&context.unexpected==0).put("error",error==null?JSONObject.NULL:error).put("tests",cases.length()).put("failures",failures).put("setupFailures",setupFailures).put("cases",cases).put("sourceSha256",source==null?JSONObject.NULL:digest(source)).put("sourceBytes",source==null?0:source.length()).put("fixturePackage",getPackageName()).put("sdk",Build.VERSION.SDK_INT).put("internetPermission",checkSelfPermission("android.permission.INTERNET")==0).put("actualEmbeddedOrigin",origin).put("constructed",constructed).put("disposed",disposed).put("allDefaultsRestored",defaultsRestored).put("unexpectedContextOperations",context.unexpected).put("realClockSettingsChanged",false).put("scope","Actual detached debug Android SplitFlap constructor, read-only formatter, actual reveal/first resumed tick with supplied literal dates; generated resource context only. No attachment/drawing/flip animation/night-motion/provider/prefs/network/optimized/API28/Living or concurrent defaults acceptance.");try(OutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),"report.json"))){out.write(r.toString(2).getBytes("UTF-8"));}}catch(Throwable ignored){}finish();}
 protected void onDestroy(){if(!closed)finishReport("owner_destroyed");super.onDestroy();}
}
