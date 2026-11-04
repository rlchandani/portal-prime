/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.apkbrowserfixture;
import android.app.Activity;
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

public final class ApkBrowserActivityFixture extends Activity {
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final JSONArray cases=new JSONArray();
 private static final byte[] A={65,80,75,65,11,22},B={65,80,75,66,33,44},GUARD={71,85,65,82,68};
 private Object helper;private Method prepare;private Class<?> function;private ClassLoader loader;private File source;private boolean closed;private int failures;
 private interface Case {void run(Fixture f)throws Throwable;}
 private interface Metadata {Object read(File f)throws Throwable;}
 private final class Fixture {
  final File dir,download,cache,sourceGuard,cacheGuard;final List<Object> prepared=new ArrayList<>();byte[] expected=A;int callbackCount;
  Fixture(String name)throws Exception{dir=new File(getFilesDir(),"generated-"+name);check(dir.mkdirs(),"Fresh fixture directory");download=new File(dir,"download.apk");cache=new File(dir,"cache");check(cache.mkdir(),"Own cache");sourceGuard=new File(dir,"source-guard.bin");cacheGuard=new File(cache,"guard.bin");write(download,A);write(sourceGuard,GUARD);write(cacheGuard,B);}
  Object select(File file,Metadata metadata)throws Throwable{
   Object callback=Proxy.newProxyInstance(loader,new Class<?>[]{function},(proxy,method,args)->{if(method.getName().equals("invoke")){callbackCount++;return metadata.read((File)args[0]);}if(method.getName().equals("toString"))return "OwnedGeneratedMetadata";if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);if(method.getName().equals("equals"))return proxy==args[0];throw new AssertionError("Unexpected callback method "+method.getName());});
   Object result=invoke(prepare,helper,cache,file,callback);if(result!=null)prepared.add(result);return result;
  }
  Object select()throws Throwable{return select(download,f->Integer.valueOf(read(f)[3]&255));}
 }
 public void onCreate(Bundle state){super.onCreate(state);handler.postDelayed(()->finishReport("owner_timeout"),30000);new Thread(this::probe,"OwnedApkBrowserSelection").start();}
 private void probe(){try{
  source=new File(getCacheDir(),"source.apk");try(InputStream in=getAssets().open("source.apk");OutputStream out=new FileOutputStream(source)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);}
  DexClassLoader dex=new DexClassLoader(source.getAbsolutePath(),getCodeCacheDir().getAbsolutePath(),null,getClassLoader().getParent());loader=dex;Class<?> type=dex.loadClass("com.immortal.launcher.ApkBrowserSelection");check(type.getClassLoader()==dex,"Actual embedded APK class loader");function=dex.loadClass("kotlin.jvm.functions.Function1");helper=type.getField("INSTANCE").get(null);prepare=type.getMethod("prepare",File.class,File.class,function);
  runCase("replacementAfterConfirmationRetainsConfirmedBytesAndMetadata",f->{Object p=f.select();check(p!=null,"Selection");f.expected=B;write(f.download,B);check(metadata(p).equals(65),"Confirmed metadata A");check(Arrays.equals(read(file(p)),A),"Confirmed bytes A remain");close(p);check(Arrays.equals(read(f.download),B),"Replaced source B survives");check(f.callbackCount==1,"Metadata once");});
  runCase("deletingDownloadAfterConfirmationRetainsInstallerInput",f->{Object p=f.select();check(p!=null,"Selection");check(f.download.delete(),"Generated download deleted");f.expected=null;check(Arrays.equals(read(file(p)),A),"Confirmed installer bytes remain after source deletion");close(p);check(f.callbackCount==1,"Metadata once");});
  runCase("metadataReadsSamePrivateFileHandedToInstaller",f->{final File[] parsed={null};Object p=f.select(f.download,x->{parsed[0]=x;check(Arrays.equals(read(x),A),"Metadata parses generated A");return "Generated app A";});check(p!=null,"Selection");check(file(p).equals(parsed[0]),"Metadata and installer file identical");check(file(p).getParentFile().getCanonicalFile().equals(f.cache.getCanonicalFile()),"Private cache parent");check(!file(p).getCanonicalFile().equals(f.download.getCanonicalFile()),"Private file differs from source");check(metadata(p).equals("Generated app A"),"Metadata retained");close(p);check(f.callbackCount==1,"Metadata once");});
  runCase("closeRemovesOnlyOwnedCopyAndCanRepeat",f->{Object p=f.select();check(p!=null,"Selection");File selected=file(p);close(p);close(p);check(!selected.exists(),"Owned copy removed");check(Arrays.equals(read(f.download),A),"Original preserved");check(cacheOnlyGuard(f),"Foreign cache file preserved and no own copy left");check(f.callbackCount==1,"Metadata once");});
  runCase("successiveSelectionsKeepBothCompletedCopies",f->{Object a=f.select();f.expected=B;write(f.download,B);Object b=f.select();check(a!=null&&b!=null,"Two selections");check(!file(a).equals(file(b)),"Distinct owned selection files");check(Arrays.equals(read(file(a)),A)&&Arrays.equals(read(file(b)),B),"Each confirmed selection bytes");check(metadata(a).equals(65)&&metadata(b).equals(66),"Each selection metadata");close(a);check(Arrays.equals(read(file(b)),B),"Closing A keeps B");close(b);check(Arrays.equals(read(f.download),B),"Latest source preserved");check(cacheOnlyGuard(f),"Both owned files retired");check(f.callbackCount==2,"Metadata twice");});
  runCase("unreadableMetadataLeavesSourceAndForeignCache",f->{Object p=f.select(f.download,x->null);check(p==null,"Null metadata rejects");check(Arrays.equals(read(f.download),A),"Source intact");check(cacheOnlyGuard(f),"Null metadata removes own copy only");check(f.callbackCount==1,"Metadata once");});
  runCase("metadataFailureCleansOwnedCopyPreservesOriginal",f->{RuntimeException generated=new IllegalStateException("generated metadata failure");Throwable caught=null;try{f.select(f.download,x->{throw generated;});}catch(Throwable t){caught=t;}check(caught==generated,"Exact generated failure rethrown");check(Arrays.equals(read(f.download),A),"Original intact after metadata failure");check(cacheOnlyGuard(f),"Failure removes own copy only");check(f.callbackCount==1,"Metadata once");});
  runCase("missingDownloadFailsWithoutCallingMetadata",f->{check(f.download.delete(),"Own missing source arranged");f.expected=null;Object p=f.select(f.download,x->"must not parse missing source");check(p==null,"Missing source rejects");check(f.callbackCount==0,"Missing source never invokes metadata");check(cacheOnlyGuard(f),"No abandoned missing-source copy");});
  finishReport(null);
 }catch(Throwable t){finishReport("setup:"+describe(t));}}
 private void runCase(String name,Case body)throws Exception{Fixture f=new Fixture(name);String error=null;try{body.run(f);}catch(Throwable t){error=describe(t);}finally{for(Object p:f.prepared)try{close(p);}catch(Throwable t){error=(error==null?"":error+"; ")+"retirement:"+describe(t);}}
  boolean sourceGuard=Arrays.equals(read(f.sourceGuard),GUARD),cacheGuard=Arrays.equals(read(f.cacheGuard),B),sourceState=f.expected==null?!f.download.exists():f.download.isFile()&&Arrays.equals(read(f.download),f.expected);if(!sourceGuard||!cacheGuard||!sourceState)error=(error==null?"":error+"; ")+"Fixture source/cache ownership changed";boolean passed=error==null;if(!passed)failures++;cases.put(new JSONObject().put("name",name).put("passed",passed).put("error",error==null?JSONObject.NULL:error).put("sourceGuardUnchanged",sourceGuard).put("cacheGuardUnchanged",cacheGuard).put("sourceStateExpected",sourceState).put("metadataCallbackCount",f.callbackCount));
 }
 private static boolean cacheOnlyGuard(Fixture f){String[] names=f.cache.list();return names!=null&&names.length==1&&names[0].equals("guard.bin");}
 private static File file(Object p)throws Throwable{return (File)invoke(p.getClass().getMethod("getFile"),p);}
 private static Object metadata(Object p)throws Throwable{return invoke(p.getClass().getMethod("getMetadata"),p);}
 private static void close(Object p)throws Throwable{invoke(p.getClass().getMethod("close"),p);}
 private static Object invoke(Method method,Object receiver,Object...args)throws Throwable{try{return method.invoke(receiver,args);}catch(InvocationTargetException t){throw t.getCause();}}
 private static byte[] read(File f)throws IOException{try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[256];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
 private static void write(File f,byte[] bytes)throws IOException{try(OutputStream out=new FileOutputStream(f)){out.write(bytes);}}
 private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 private static String describe(Throwable t){return t.getClass().getName()+":"+String.valueOf(t.getMessage());}
 private static String digest(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);}StringBuilder result=new StringBuilder();for(byte b:md.digest())result.append(String.format(Locale.US,"%02x",b&255));return result.toString();}
 private synchronized void finishReport(String error){if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);try{JSONObject report=new JSONObject().put("ok",error==null&&cases.length()==8&&failures==0).put("error",error==null?JSONObject.NULL:error).put("tests",cases.length()).put("failures",failures).put("cases",cases).put("sourceSha256",source==null?JSONObject.NULL:digest(source)).put("sourceBytes",source==null?0:source.length()).put("sdk",android.os.Build.VERSION.SDK_INT).put("actualEmbeddedApkClassLoader",helper!=null).put("actualPublicHelperMethods",prepare!=null).put("fixturePackage",getPackageName()).put("internetPermission",checkSelfPermission("android.permission.INTERNET")==0).put("generatedFileOnly",true).put("scope","Actual embedded debug APK selection helper with generated owned files only; no APK installation, launcher owner data, media, providers, network, UI, optimized/API28, concurrency or collision claim");try(OutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),"report.json"))){out.write(report.toString(2).getBytes("UTF-8"));}}catch(Exception ignored){}runOnUiThread(this::finish);}
 protected void onDestroy(){if(!closed)finishReport("owner_destroyed");super.onDestroy();}
}
