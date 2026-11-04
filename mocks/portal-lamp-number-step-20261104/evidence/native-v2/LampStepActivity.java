/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.lampstepfixture;
import android.app.Activity;
import android.os.*;
import dalvik.system.DexClassLoader;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Actual compiled private callbacks only; generated draft/error states, no Compose or Lamp startup. */
public final class LampStepActivity extends Activity {
 private static final String MINUS="LampControlDialog$lambda$106$lambda$105$lambda$104$lambda$100$lambda$97$lambda$96";
 private static final String PLUS="LampControlDialog$lambda$106$lambda$105$lambda$104$lambda$100$lambda$99$lambda$98";
 private final Handler owner=new Handler(Looper.getMainLooper());private final JSONArray cases=new JSONArray();
 private File source;private DexClassLoader loader;private Class<?> callbacksType,controlType,stateType,unitType;private Method minus,plus;private Object unit;
 private boolean closed,origin;private int failures,setupFailures,unexpectedStateFunctions;
 @Override public void onCreate(Bundle b){super.onCreate(b);owner.postDelayed(()->finishReport("owner_timeout"),30000);owner.post(this::probe);}
 private void probe(){String error=null;try{
  source=new File(getCacheDir(),"source.apk");try(InputStream in=getAssets().open("source.apk");OutputStream out=new FileOutputStream(source)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);}if(!source.setReadOnly())throw new IOException("Cannot freeze private source.apk");
  loader=new DexClassLoader(source.getAbsolutePath(),getCodeCacheDir().getAbsolutePath(),null,getClassLoader().getParent());callbacksType=loader.loadClass("com.immortal.launcher.LampActivityKt");controlType=loader.loadClass("com.immortal.launcher.ApprovedLampControl");stateType=loader.loadClass("androidx.compose.runtime.MutableState");unitType=loader.loadClass("kotlin.Unit");
  for(Class<?> c:new Class<?>[]{callbacksType,controlType,stateType,unitType})check(c.getClassLoader()==loader,"Actual embedded class origin "+c.getName());origin=true;check(controlType.isEnum()&&stateType.isInterface(),"Actual enum and MutableState admission");unit=unitType.getField("INSTANCE").get(null);
  minus=bind(MINUS);plus=bind(PLUS);
  Object brightness=constant("BRIGHTNESS"),warmth=constant("WARMTH");check(((Number)controlType.getMethod("getMin").invoke(brightness)).intValue()==5,"Brightness literal min5");check(((Number)controlType.getMethod("getMin").invoke(warmth)).intValue()==0,"Warmth literal min0");
  runCase("brightnessMaxIntPlus",brightness,"2147483647",true,"100");runCase("brightnessMinIntMinus",brightness,"-2147483648",false,"5");runCase("brightness90Plus",brightness,"90",true,"91");runCase("brightnessMinMinus",brightness,"5",false,"5");runCase("brightness100Plus",brightness,"100",true,"100");runCase("brightnessBlankPlus",brightness,"",true,"6");
  runCase("warmthMaxIntPlus",warmth,"2147483647",true,"100");runCase("warmthMinIntMinus",warmth,"-2147483648",false,"0");runCase("warmth90Plus",warmth,"90",true,"91");runCase("warmthMinMinus",warmth,"0",false,"0");runCase("warmth100Plus",warmth,"100",true,"100");runCase("warmthBlankPlus",warmth,"",true,"1");
 }catch(Throwable t){error="setup:"+describe(t);setupFailures++;}finishReport(error);}
 private Method bind(String name)throws Exception{Method m=callbacksType.getDeclaredMethod(name,controlType,stateType,stateType);check(m.getModifiers()==(Modifier.STATIC|Modifier.FINAL)&&m.getReturnType()==unitType,"Exact private static 3-parameter Unit callback "+name);m.setAccessible(true);return m;}
 private Object constant(String name){for(Object c:controlType.getEnumConstants())if(((Enum<?>)c).name().equals(name))return c;throw new AssertionError("Missing literal control "+name);}
 private void runCase(String name,Object control,String input,boolean increment,String expected)throws Exception{
  State draft=new State(input),error=new State("generated-existing-error");List<String> errors=new ArrayList<>();String invocationError=null;boolean unitReturned=false;
  try{Object result=(increment?plus:minus).invoke(null,control,draft.proxy,error.proxy);unitReturned=result==unit;require(errors,unitReturned,"Actual callback returns loaded Unit");}catch(Throwable t){invocationError=describe(t);errors.add("actual callback:"+invocationError);}
  require(errors,expected.equals(draft.value),"Literal draft expected="+expected+" actual="+draft.value);require(errors,error.value==null,"Existing error cleared to null");
  require(errors,draft.gets==1&&draft.sets==1,"Draft exactly one read and write");require(errors,error.gets==0&&error.sets==1,"Error exactly zero reads and one null write");require(errors,unexpectedStateFunctions==0,"No unexpected state functions");
  boolean pass=errors.isEmpty();if(!pass)failures++;
  cases.put(new JSONObject().put("name",name).put("passed",pass).put("control",((Enum<?>)control).name()).put("input",input).put("operation",increment?"plus":"minus").put("handler",increment?PLUS:MINUS).put("expected",expected).put("actual",draft.value==null?JSONObject.NULL:draft.value).put("errorAfter",error.value==null?JSONObject.NULL:error.value).put("draftReads",draft.gets).put("draftWrites",draft.sets).put("errorReads",error.gets).put("errorWrites",error.sets).put("unitReturned",unitReturned).put("invocationError",invocationError==null?JSONObject.NULL:invocationError).put("errors",new JSONArray(errors)).put("unexpectedStateFunctions",unexpectedStateFunctions));
 }
 private final class State implements InvocationHandler {
  Object value;int gets,sets;final Object proxy;State(Object value){this.value=value;proxy=Proxy.newProxyInstance(loader,new Class<?>[]{stateType},this);}
  @Override public Object invoke(Object p,Method m,Object[] a){String n=m.getName();if(n.equals("getValue")&&(a==null||a.length==0)){gets++;return value;}if(n.equals("setValue")&&a!=null&&a.length==1){sets++;value=a[0];return null;}if(n.equals("toString"))return "GeneratedLampState";if(n.equals("hashCode"))return System.identityHashCode(p);if(n.equals("equals"))return p==a[0];unexpectedStateFunctions++;throw new AssertionError("Unexpected generated state function "+m);}
 }
 private static void require(List<String> errors,boolean ok,String why){if(!ok)errors.add(why);}private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 private static String describe(Throwable t){while(t instanceof InvocationTargetException&&t.getCause()!=null)t=t.getCause();return t.getClass().getName()+":"+t.getMessage();}
 private static String digest(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
 private synchronized void finishReport(String error){if(closed)return;closed=true;owner.removeCallbacksAndMessages(null);try{JSONObject r=new JSONObject().put("ok",error==null&&cases.length()==12&&failures==0&&setupFailures==0&&origin&&unexpectedStateFunctions==0).put("error",error==null?JSONObject.NULL:error).put("tests",cases.length()).put("failures",failures).put("setupFailures",setupFailures).put("cases",cases).put("sourceSha256",source==null?JSONObject.NULL:digest(source)).put("sourceBytes",source==null?0:source.length()).put("actualEmbeddedOrigin",origin).put("minusHandler",MINUS).put("plusHandler",PLUS).put("exactCallbackSignatures",minus!=null&&plus!=null).put("unexpectedStateFunctions",unexpectedStateFunctions).put("sdk",Build.VERSION.SDK_INT).put("fixturePackage",getPackageName()).put("internetPermission",checkSelfPermission("android.permission.INTERNET")==0).put("targetContextPassed",false).put("targetComposeRendered",false).put("targetLampStarted",false).put("targetSaveInvoked",false).put("ownerHandlerRetired",true).put("scope","Actual isolated debug private Lamp dialog minus/plus callbacks with generated String draft and nullable error MutableState proxies only. No Compose rendering, actual touch, Context passed to target, Save/lamp/window/photo/services/config/prefs/provider/network/optimized/API28 or room visual acceptance. DexClassLoader is not closed; root retires this owned Activity/package/process/external files.");try(OutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),"report.json"))){out.write(r.toString(2).getBytes("UTF-8"));}}catch(Throwable ignored){}finish();}
 @Override protected void onDestroy(){if(!closed)finishReport("owner_destroyed");super.onDestroy();}
}
