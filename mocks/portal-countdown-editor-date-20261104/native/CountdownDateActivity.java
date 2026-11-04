/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.countdowndatefixture;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import dalvik.system.DexClassLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class CountdownDateActivity extends Activity {
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final JSONArray cases=new JSONArray(),preferenceRequests=new JSONArray();
 private final AtomicInteger unexpectedPreferenceCalls=new AtomicInteger();
 private File source;private boolean closed;private int failures;
 private DexClassLoader loader;private Method addCallback,loadSorted;private Object config;private boolean newCaptureOrder;
 private static final String RAW="[{\"id\":600,\"label\":\"Existing invalid legacy\",\"emoji\":\"📅\",\"month\":2,\"day\":31,\"year\":0},{\"id\":601,\"label\":\"Existing valid\",\"emoji\":\"⭐\",\"month\":12,\"day\":20,\"year\":2028}]";
 private static final String DATE_ERROR="Choose a valid date for this month and year.";
 public void onCreate(Bundle b){super.onCreate(b);handler.postDelayed(()->finishReport("owner_timeout"),30000);new Thread(this::probe,"OwnedCountdownDate").start();}
 private void probe(){try{
  source=new File(getCacheDir(),"source.apk");try(InputStream in=getAssets().open("source.apk");OutputStream out=new FileOutputStream(source)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);}
  loader=new DexClassLoader(source.getAbsolutePath(),getCodeCacheDir().getAbsolutePath(),null,getClassLoader().getParent());
  Class<?> content=loader.loadClass("com.immortal.launcher.CountdownSettingsActivityKt");check(content.getClassLoader()==loader,"Actual embedded APK class loader");
  List<Method> matches=new ArrayList<>();for(Method m:content.getDeclaredMethods())if(Modifier.isStatic(m.getModifiers())&&m.getName().startsWith("CountdownSettingsContent$")&&m.getParameterTypes().length==9&&m.getParameterTypes()[0]==Context.class)matches.add(m);
  check(matches.size()==1,"Unique actual compiled Add callback with nine captured parameters");addCallback=matches.get(0);addCallback.setAccessible(true);
  String[] before={"android.content.Context","androidx.compose.runtime.MutableState","androidx.compose.runtime.MutableState","androidx.compose.runtime.MutableState","androidx.compose.runtime.MutableIntState","androidx.compose.runtime.MutableIntState","androidx.compose.runtime.MutableState","androidx.compose.runtime.MutableIntState","androidx.compose.runtime.MutableState"};String[] after={"android.content.Context","androidx.compose.runtime.MutableState","androidx.compose.runtime.MutableState","androidx.compose.runtime.MutableIntState","androidx.compose.runtime.MutableIntState","androidx.compose.runtime.MutableState","androidx.compose.runtime.MutableIntState","androidx.compose.runtime.MutableState","androidx.compose.runtime.MutableState"};Class<?>[] types=addCallback.getParameterTypes();newCaptureOrder=types[3].getName().equals("androidx.compose.runtime.MutableIntState");String[] expected=newCaptureOrder?after:before;for(int i=0;i<types.length;i++)check(types[i].getName().equals(expected[i]),"Actual callback capture interface "+i);
  Class<?> cfg=loader.loadClass("com.immortal.launcher.CountdownConfig");check(cfg.getClassLoader()==loader,"Actual CountdownConfig class loader");config=cfg.getField("INSTANCE").get(null);loadSorted=cfg.getMethod("loadSorted",Context.class);
  runCase("february31", "Trip",2,31,2028,false,false,DATE_ERROR);
  runCase("april31", "Trip",4,31,2028,false,false,DATE_ERROR);
  runCase("commonYearFebruary29", "Trip",2,29,2026,false,false,DATE_ERROR);
  runCase("nonLeapCenturyFebruary29", "Trip",2,29,2100,false,false,DATE_ERROR);
  runCase("yearlyFebruary30", "Trip",2,30,2028,true,false,DATE_ERROR);
  runCase("blankName", "   ",1,1,2028,false,false,"Enter a name.");
  runCase("validFebruary28", "Trip",2,28,2026,false,true,null);
  runCase("validLeapYearFebruary29", "Trip",2,29,2028,false,true,null);
  runCase("validYearlyFebruary29", "Trip",2,29,2026,true,true,null);
  runCase("validLongNameRetainsExistingPolicy", repeat("n",81),12,31,2028,false,true,null);
  finishReport(null);
 }catch(Throwable t){finishReport("setup:"+describe(t));}}
 private static final class Ref implements InvocationHandler {
  Object value;final Object proxy;Ref(DexClassLoader l,Class<?> type,Object value){this.value=value;proxy=Proxy.newProxyInstance(l,new Class<?>[]{type},this);}
  public Object invoke(Object p,Method m,Object[] args){String n=m.getName();if(n.equals("getValue")||n.equals("getIntValue")||n.equals("component1"))return value;if(n.equals("setValue")||n.equals("setIntValue")){value=args[0];return null;}if(n.equals("toString"))return "GeneratedState("+value+")";if(n.equals("hashCode"))return System.identityHashCode(p);if(n.equals("equals"))return p==args[0];throw new IllegalStateException("Unexpected state method "+m);}
 }
 private static final class Counts {int edits,applies,commits,reads;}
 private SharedPreferences observed(SharedPreferences prefs,Counts counts){return (SharedPreferences)Proxy.newProxyInstance(getClassLoader(),new Class<?>[]{SharedPreferences.class},(p,m,a)->{if(m.getName().equals("edit")){counts.edits++;SharedPreferences.Editor actual=prefs.edit();return Proxy.newProxyInstance(getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(ep,em,ea)->{if(em.getName().equals("apply"))counts.applies++;if(em.getName().equals("commit"))counts.commits++;Object out=em.invoke(actual,ea);return out instanceof SharedPreferences.Editor?ep:out;});}return m.invoke(prefs,a);});}
 private void runCase(String name,String draft,int month,int day,int year,boolean yearly,boolean shouldSave,String expectedError)throws Exception {
  SharedPreferences prefs=getSharedPreferences("generated-"+name,MODE_PRIVATE);check(prefs.edit().clear().putString("events",RAW).putString("sentinel","fixture-only-unchanged").putInt("sentinelNumber",314159).commit(),"Owned preference seed");
  Counts counts=new Counts();SharedPreferences guarded=observed(prefs,counts);
  Context isolated=new ContextWrapper(this){public SharedPreferences getSharedPreferences(String requested,int mode){if(!requested.equals("immortal_countdown")||mode!=MODE_PRIVATE){unexpectedPreferenceCalls.incrementAndGet();throw new IllegalStateException("Unexpected preference request");}counts.reads++;synchronized(preferenceRequests){try{preferenceRequests.put(new JSONObject().put("case",name).put("name",requested).put("mode",mode));}catch(Exception ignored){}}return guarded;}public Context getApplicationContext(){return this;}};
  List<?> before=(List<?>)loadSorted.invoke(config,isolated);counts.reads=0;counts.edits=0;counts.applies=0;counts.commits=0;
  Class<?>[] types=addCallback.getParameterTypes();Class<?> state=types[1],integer=types[newCaptureOrder?3:4];Ref label=new Ref(loader,state,draft),error=new Ref(loader,state,"prior error"),emoji=new Ref(loader,state,"🎂"),m=new Ref(loader,integer,month),d=new Ref(loader,integer,day),annual=new Ref(loader,state,yearly),y=new Ref(loader,integer,year),events=new Ref(loader,state,before);
  List<String> errors=new ArrayList<>();try{if(newCaptureOrder)addCallback.invoke(null,isolated,label.proxy,error.proxy,m.proxy,d.proxy,annual.proxy,y.proxy,emoji.proxy,events.proxy);else addCallback.invoke(null,isolated,label.proxy,error.proxy,emoji.proxy,m.proxy,d.proxy,annual.proxy,y.proxy,events.proxy);}catch(Throwable t){errors.add("callback:"+describe(t));}
  boolean sentinel="fixture-only-unchanged".equals(prefs.getString("sentinel",null))&&prefs.getInt("sentinelNumber",0)==314159;
  require(errors,sentinel,"Sentinel unchanged");require(errors,counts.commits==0,"No callback commit");
  require(errors,emoji.value.equals("🎂")&&m.value.equals(month)&&d.value.equals(day)&&annual.value.equals(yearly)&&y.value.equals(year),"Other draft values retained");
  if(!shouldSave){require(errors,counts.edits==0&&counts.applies==0,"Rejected draft never creates editor or applies");require(errors,RAW.equals(prefs.getString("events",null)),"Rejected draft exact legacy bytes");require(errors,draft.equals(label.value),"Rejected label retained");require(errors,events.value==before,"Rejected events reference retained");require(errors,Objects.equals(expectedError,error.value),"Exact validation error");}
  else {require(errors,counts.edits==1&&counts.applies==1,"Accepted draft one editor/apply");require(errors,"".equals(label.value)&&error.value==null,"Accepted label/error cleared");try{JSONArray arr=new JSONArray(prefs.getString("events",null));require(errors,arr.length()==3,"Accepted exactly one new row");JSONArray prior=new JSONArray(RAW);for(int i=0;i<2;i++)require(errors,sameFields(prior.getJSONObject(i),arr.getJSONObject(i)),"Legacy row fields and order retained "+i);JSONObject last=arr.getJSONObject(2);require(errors,last.getString("label").equals(draft)&&last.getString("emoji").equals("🎂")&&last.getInt("month")==month&&last.getInt("day")==day&&last.getInt("year")==(yearly?0:year),"New exact saved fields");List<?> reloaded=(List<?>)events.value;require(errors,reloaded!=before&&reloaded.size()==3,"Accepted actual sorted events reloaded");Set<Long> ids=new HashSet<>();for(Object e:reloaded)ids.add(((Number)e.getClass().getMethod("getId").invoke(e)).longValue());require(errors,ids.contains(600L)&&ids.contains(601L)&&ids.contains(last.getLong("id"))&&ids.size()==3,"Reload preserves old and new identities");}catch(Throwable t){errors.add("stored:"+describe(t));}}
  boolean passed=errors.isEmpty();if(!passed)failures++;JSONObject c=new JSONObject().put("name",name).put("passed",passed).put("errors",new JSONArray(errors)).put("shouldSave",shouldSave).put("sentinelUnchanged",sentinel).put("edits",counts.edits).put("applies",counts.applies).put("commits",counts.commits).put("callbackPreferenceRequests",counts.reads).put("rawUnchanged",RAW.equals(prefs.getString("events",null))).put("labelAfter",label.value).put("errorAfter",error.value==null?JSONObject.NULL:error.value).put("eventsReferenceUnchanged",events.value==before).put("otherDraftValuesUnchanged",emoji.value.equals("🎂")&&m.value.equals(month)&&d.value.equals(day)&&annual.value.equals(yearly)&&y.value.equals(year));cases.put(c);
 }
 private static boolean sameFields(JSONObject a,JSONObject b)throws Exception {for(String key:new String[]{"id","label","emoji","month","day","year"})if(!a.get(key).equals(b.get(key)))return false;return true;}
 private static void require(List<String> errors,boolean condition,String message){if(!condition)errors.add(message);}
 private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 private static String repeat(String s,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(s);return out.toString();}
 private static String describe(Throwable t){if(t instanceof InvocationTargetException&&((InvocationTargetException)t).getCause()!=null)t=((InvocationTargetException)t).getCause();return t.getClass().getName()+":"+String.valueOf(t.getMessage());}
 private static String digest(File file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);}StringBuilder result=new StringBuilder();for(byte b:md.digest())result.append(String.format(Locale.US,"%02x",b&255));return result.toString();}
 private synchronized void finishReport(String error){if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);try{JSONObject report=new JSONObject().put("ok",error==null&&cases.length()==10&&failures==0&&unexpectedPreferenceCalls.get()==0).put("error",error==null?JSONObject.NULL:error).put("tests",cases.length()).put("failures",failures).put("cases",cases).put("preferenceRequests",preferenceRequests).put("unexpectedPreferenceCalls",unexpectedPreferenceCalls.get()).put("sourceSha256",source==null?JSONObject.NULL:digest(source)).put("sourceBytes",source==null?0:source.length()).put("sdk",android.os.Build.VERSION.SDK_INT).put("actualEmbeddedApkClassLoader",config!=null).put("actualCompiledAddCallback",addCallback!=null).put("callbackName",addCallback==null?JSONObject.NULL:addCallback.getName()).put("captureBinding",newCaptureOrder?"after-month-first":"before-emoji-first").put("fixturePackage",getPackageName()).put("internetPermission",checkSelfPermission("android.permission.INTERNET")==0).put("scope","Actual compiled standalone editor Add callback only with JavaProxy draft state and generated fixture-local preferences; no mounted Compose UI, physical clicks, owner data, provider, network, optimized/API28 acceptance");try(OutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),"report.json"))){out.write(report.toString(2).getBytes("UTF-8"));}}catch(Exception ignored){}runOnUiThread(this::finish);}
 protected void onDestroy(){if(!closed)finishReport("owner_destroyed");super.onDestroy();}
}
