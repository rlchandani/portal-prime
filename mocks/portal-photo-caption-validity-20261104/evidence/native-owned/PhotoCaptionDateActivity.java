/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.captiondatefixture;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.*;
import dalvik.system.DexClassLoader;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Actual embedded EXIF/read/format/memory APIs with generated in-memory JPEG metadata only. */
public final class PhotoCaptionDateActivity extends Activity {
 private static final long MEMORY_NOW=1898683200000L; // Literal2030-03-02T12:00:00Z.
 private static final double LAT=48.8566,LNG=2.3522,GPS_TOLERANCE=.00001;
 private final Handler owner=new Handler(Looper.getMainLooper());private final JSONArray cases=new JSONArray();
 private File source;private DexClassLoader loader;private Class<?> captionType,exifType,metaType;private Object caption;private Constructor<?> exifConstructor;
 private Method read,formatDate,memoryYears,setAttribute,getAttribute,setLatLong,getDate,getLat,getLng,hasLocation,isEmpty;
 private String originalTag,fallbackTag;private byte[] jpeg;private TimeZone oldZone;private Locale oldLocale,oldFormat,oldDisplay;
 private boolean closed,origin,bitmapRecycled,defaultsRestored;private int failures,setupFailures,streamsConstructed,streamsClosed;
 @Override public void onCreate(Bundle b){super.onCreate(b);owner.postDelayed(()->finishReport("owner_timeout"),30000);owner.post(this::probe);}
 private void probe(){oldZone=TimeZone.getDefault();oldLocale=Locale.getDefault();oldFormat=Locale.getDefault(Locale.Category.FORMAT);oldDisplay=Locale.getDefault(Locale.Category.DISPLAY);String error=null;try{
  source=new File(getCacheDir(),"source.apk");try(InputStream in=getAssets().open("source.apk");OutputStream out=new FileOutputStream(source)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);}check(source.setReadOnly(),"Private source APK read-only");loader=new DexClassLoader(source.getAbsolutePath(),getCodeCacheDir().getAbsolutePath(),null,getClassLoader().getParent());
  captionType=loader.loadClass("com.immortal.launcher.PhotoCaption");exifType=loader.loadClass("androidx.exifinterface.media.ExifInterface");metaType=loader.loadClass("com.immortal.launcher.PhotoCaption$Meta");for(Class<?> c:new Class<?>[]{captionType,exifType,metaType})check(c.getClassLoader()==loader,"Actual embedded class origin "+c.getName());origin=true;
  caption=captionType.getField("INSTANCE").get(null);exifConstructor=exifType.getConstructor(InputStream.class);read=captionType.getMethod("read",exifType);formatDate=captionType.getMethod("formatDate",Long.class);memoryYears=captionType.getMethod("memoryYears",Long.class,long.class);
  setAttribute=exifType.getMethod("setAttribute",String.class,String.class);getAttribute=exifType.getMethod("getAttribute",String.class);setLatLong=exifType.getMethod("setLatLong",double.class,double.class);originalTag=(String)exifType.getField("TAG_DATETIME_ORIGINAL").get(null);fallbackTag=(String)exifType.getField("TAG_DATETIME").get(null);
  getDate=metaType.getMethod("getDateMillis");getLat=metaType.getMethod("getLat");getLng=metaType.getMethod("getLng");hasLocation=metaType.getMethod("getHasLocation");isEmpty=metaType.getMethod("isEmpty");
  Bitmap generated=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);try{generated.eraseColor(0xff597681);ByteArrayOutputStream bytes=new ByteArrayOutputStream();check(generated.compress(Bitmap.CompressFormat.JPEG,100,bytes),"Generated JPEG compression");jpeg=bytes.toByteArray();check(jpeg.length>0,"Generated JPEG bytes");}finally{generated.recycle();bitmapRecycled=generated.isRecycled();}
  runCase("invalidFebruary30","2023:02:30 12:00:00",null,false,"UTC",null,null);
  runCase("invalidNonLeapFebruary29","2023:02:29 12:00:00",null,false,"UTC",null,null);
  runCase("invalid1900February29","1900:02:29 12:00:00",null,false,"UTC",null,null);
  runCase("invalidApril31","2023:04:31 12:00:00",null,false,"UTC",null,null);
  runCase("invalidMonth13","2023:13:01 12:00:00",null,false,"UTC",null,null);
  runCase("invalidDayZero","2023:01:00 12:00:00",null,false,"UTC",null,null);
  runCase("invalidHour24","2023:01:01 24:00:00",null,false,"UTC",null,null);
  runCase("invalidOriginalRetainsPrecedenceOverValidFallback","2023:02:30 12:00:00","2023:02:28 12:00:00",false,"UTC",null,null);
  runCase("invalidFebruary30RetainsGps","2023:02:30 12:00:00",null,true,"UTC",null,null);
  runCase("valid2024LeapFebruary29","2024:02:29 12:00:00",null,false,"UTC",1709208000000L,"February 29, 2024");
  runCase("valid2000LeapFebruary29","2000:02:29 12:00:00",null,false,"UTC",951825600000L,"February 29, 2000");
  runCase("valid2023February28","2023:02:28 12:00:00",null,false,"UTC",1677585600000L,"February 28, 2023");
  runCase("missingOriginalUsesValidFallback",null,"2023:02:28 12:00:00",false,"UTC",1677585600000L,"February 28, 2023");
  runCase("absentDates",null,null,false,"UTC",null,null);
  runCase("gpsWithValidDate","2023:02:28 12:00:00",null,true,"UTC",1677585600000L,"February 28, 2023");
  runCase("losAngelesWallTimeStillUsesCurrentZone","2020:01:01 00:30:00",null,false,"America/Los_Angeles",1577867400000L,"January 1, 2020");
 }catch(Throwable t){error="setup:"+describe(t);setupFailures++;}finally{restore();defaultsRestored=restored();}finishReport(error);}
 private void runCase(String name,String original,String fallback,boolean gps,String zone,Long expectedDate,String expectedFormatted)throws Exception{
  TimeZone.setDefault(TimeZone.getTimeZone(zone));Locale.setDefault(Locale.US);Locale.setDefault(Locale.Category.FORMAT,Locale.US);Locale.setDefault(Locale.Category.DISPLAY,Locale.US);
  ByteArrayInputStream stream=new ByteArrayInputStream(jpeg);streamsConstructed++;List<String> errors=new ArrayList<>();String setup=null;Long actualDate=null;Double lat=null,lng=null;Object formatted=null,years=null;boolean actualRead=false,location=false,empty=false;String originalAfter=null,fallbackAfter=null;
  try{
   Object exif=exifConstructor.newInstance(stream);setAttribute.invoke(exif,originalTag,original);setAttribute.invoke(exif,fallbackTag,fallback);if(gps)setLatLong.invoke(exif,LAT,LNG);
   originalAfter=(String)getAttribute.invoke(exif,originalTag);fallbackAfter=(String)getAttribute.invoke(exif,fallbackTag);check(Objects.equals(original,originalAfter)&&Objects.equals(fallback,fallbackAfter),"Actual EXIF retains exact generated raw date tags before read");
   Object meta=read.invoke(caption,exif);check(meta!=null&&meta.getClass()==metaType,"Actual read returns embedded Meta");actualRead=true;actualDate=(Long)getDate.invoke(meta);lat=(Double)getLat.invoke(meta);lng=(Double)getLng.invoke(meta);location=(Boolean)hasLocation.invoke(meta);empty=(Boolean)isEmpty.invoke(meta);
   formatted=formatDate.invoke(caption,new Object[]{actualDate});years=memoryYears.invoke(caption,new Object[]{actualDate,MEMORY_NOW});
   require(errors,Objects.equals(expectedDate,actualDate),"Literal nullable epoch expected="+expectedDate+" actual="+actualDate);require(errors,Objects.equals(expectedFormatted,formatted),"Literal friendly date expected="+expectedFormatted+" actual="+formatted);require(errors,years==null,"No fabricated on-this-day badge at literal2030March2noon");
   boolean gpsPass=gps?lat!=null&&lng!=null&&Math.abs(lat-LAT)<=GPS_TOLERANCE&&Math.abs(lng-LNG)<=GPS_TOLERANCE:lat==null&&lng==null;require(errors,gpsPass,"Actual EXIF GPS retained independently of date validity");require(errors,location==gps,"hasLocation matches generated GPS");require(errors,empty==(expectedDate==null&&!gps),"Meta.isEmpty honors date/location independently");
   require(errors,Objects.equals(original,getAttribute.invoke(exif,originalTag))&&Objects.equals(fallback,getAttribute.invoke(exif,fallbackTag)),"Caption read leaves raw EXIF tags unchanged");
  }catch(Throwable t){if(!actualRead){setup=describe(t);setupFailures++;}else errors.add("actual metadata consumer:"+describe(t));}finally{stream.close();streamsClosed++;}
  boolean pass=setup==null&&errors.isEmpty();if(!pass)failures++;cases.put(new JSONObject().put("name",name).put("passed",pass).put("zone",zone).put("original",original==null?JSONObject.NULL:original).put("fallback",fallback==null?JSONObject.NULL:fallback).put("originalAfter",originalAfter==null?JSONObject.NULL:originalAfter).put("fallbackAfter",fallbackAfter==null?JSONObject.NULL:fallbackAfter).put("gps",gps).put("expectedDateMillis",expectedDate==null?JSONObject.NULL:expectedDate).put("dateMillis",actualDate==null?JSONObject.NULL:actualDate).put("expectedFormatted",expectedFormatted==null?JSONObject.NULL:expectedFormatted).put("formatted",formatted==null?JSONObject.NULL:formatted).put("memoryNowLiteral",MEMORY_NOW).put("memoryYears",years==null?JSONObject.NULL:years).put("lat",lat==null?JSONObject.NULL:lat).put("lng",lng==null?JSONObject.NULL:lng).put("gpsTolerance",GPS_TOLERANCE).put("hasLocation",location).put("isEmpty",empty).put("actualReadInvoked",actualRead).put("setupError",setup==null?JSONObject.NULL:setup).put("errors",new JSONArray(errors)).put("streamClosed",true));
 }
 private void restore(){if(oldZone!=null)TimeZone.setDefault(oldZone);if(oldLocale!=null)Locale.setDefault(oldLocale);if(oldFormat!=null)Locale.setDefault(Locale.Category.FORMAT,oldFormat);if(oldDisplay!=null)Locale.setDefault(Locale.Category.DISPLAY,oldDisplay);}
 private boolean restored(){return oldZone!=null&&TimeZone.getDefault().equals(oldZone)&&Locale.getDefault().equals(oldLocale)&&Locale.getDefault(Locale.Category.FORMAT).equals(oldFormat)&&Locale.getDefault(Locale.Category.DISPLAY).equals(oldDisplay);}
 private static void require(List<String> errors,boolean ok,String why){if(!ok)errors.add(why);}private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 private static String describe(Throwable t){while(t instanceof InvocationTargetException&&t.getCause()!=null)t=t.getCause();return t.getClass().getName()+":"+t.getMessage();}
 private static String digest(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
 private synchronized void finishReport(String error){if(closed)return;closed=true;owner.removeCallbacksAndMessages(null);if(!defaultsRestored){restore();defaultsRestored=restored();}boolean cleanup=bitmapRecycled&&streamsConstructed==streamsClosed&&defaultsRestored;try{JSONObject r=new JSONObject().put("ok",error==null&&cases.length()==16&&failures==0&&setupFailures==0&&origin&&cleanup).put("error",error==null?JSONObject.NULL:error).put("tests",cases.length()).put("failures",failures).put("setupFailures",setupFailures).put("cases",cases).put("sourceSha256",source==null?JSONObject.NULL:digest(source)).put("sourceBytes",source==null?0:source.length()).put("sdk",Build.VERSION.SDK_INT).put("fixturePackage",getPackageName()).put("internetPermission",checkSelfPermission("android.permission.INTERNET")==0).put("actualEmbeddedOrigin",origin).put("generatedJpegBytes",jpeg==null?0:jpeg.length).put("generatedBitmapRecycled",bitmapRecycled).put("streamsConstructed",streamsConstructed).put("streamsClosed",streamsClosed).put("allProcessDefaultsRestored",defaultsRestored).put("ownerHandlerRetired",true).put("cleanup",cleanup).put("targetContextPassed",false).put("targetSaveAttributesInvoked",false).put("targetPlaceLookupInvoked",false).put("scope","Actual embedded debug PhotoCaption.read and actual embedded ExifInterface from generated in-memory JPEG, setAttribute/setLatLong only; actual nullable date/GPS/formatDate/memoryYears consumers. No saveAttributes, household file/provider/prefs/Context/network/place lookup/Compose or native caption rendering, physical timezone change, optimized/API28/Living or concurrent-default acceptance. No target Context supplied; containment is source scope, not classloader-wide operation counters. Strict calendar parsing also rejects nonexistent local DST wall times; no offset/tag-repair claim. Root retires owned Activity/package/process/external files; DexClassLoader has no close claim.");try(OutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),"report.json"))){out.write(r.toString(2).getBytes("UTF-8"));}}catch(Throwable ignored){}finish();}
 @Override protected void onDestroy(){if(!closed)finishReport("owner_destroyed");super.onDestroy();}
}
