/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.photozoomwidthfixture;
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import dalvik.system.DexClassLoader;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Actual detached Stage and controls constructors/layout; no photo, action or controller startup. */
public final class PhotoZoomWidthActivity extends Activity {
 private final Handler owner=new Handler(Looper.getMainLooper());private final JSONArray cases=new JSONArray();
 private final List<Setup> active=new ArrayList<>();private File source;private DexClassLoader loader;
 private Class<?> stageType,controlsType,budgetType;private Object unit;private boolean closed,origin;private int failures,setupFailures,retired,callbacks;
 private final OwnedContext context=new OwnedContext(this);
 @Override public void onCreate(Bundle b){super.onCreate(b);owner.postDelayed(()->finishReport("owner_timeout"),30000);owner.post(this::probe);}
 private void probe(){String error=null;try{
  source=new File(getCacheDir(),"source.apk");try(InputStream in=getAssets().open("source.apk");OutputStream out=new FileOutputStream(source)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);}
  loader=new DexClassLoader(source.getAbsolutePath(),getCodeCacheDir().getAbsolutePath(),null,getClassLoader().getParent());
  stageType=loader.loadClass("com.immortal.launcher.SlideshowStage");controlsType=loader.loadClass("com.immortal.launcher.SlideshowViewerControls");budgetType=loader.loadClass("com.immortal.launcher.SlideshowPixelBudget");
  for(Class<?> c:new Class<?>[]{stageType,controlsType,budgetType})check(c.getClassLoader()==loader,"Exact embedded class origin "+c.getName());origin=true;unit=loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
  runCase("narrow320",320);runCase("narrow480",480);runCase("wide800Preserves620",800);
 }catch(Throwable t){error="setup:"+describe(t);}finishReport(error);}
 private void runCase(String name,int widthDp)throws Exception{
  Setup x=null;String setupError=null;List<String> errors=new ArrayList<>();JSONObject result=new JSONObject().put("name",name).put("widthDp",widthDp);boolean initialized=false,clean=false;
  try{
   x=new Setup();active.add(x);initialized=true;
   int width=dp(widthDp);x.row.setVisibility(View.GONE);x.editor.setVisibility(View.VISIBLE);layout(x.controls,width);
   int available=width-x.controls.getPaddingLeft()-x.controls.getPaddingRight(),expected=Math.min(dp(620),available);
   boolean contained=x.seek.getLeft()>=0&&x.seek.getRight()<=x.editor.getWidth();
   require(errors,x.controls.getMeasuredWidth()==width&&x.controls.getWidth()==width,"Actual EXACT root width");
   require(errors,x.controls.getPaddingLeft()==dp(32)&&x.controls.getPaddingRight()==dp(32),"Existing 32 dp horizontal padding");
   require(errors,x.editor.getWidth()==available&&x.editor.getLeft()==dp(32)&&x.editor.getRight()==width-dp(32),"Editor occupies padded available width");
   require(errors,contained,"Photo zoom fully within editor horizontal bounds");
   require(errors,x.seek.getWidth()==expected&&x.seek.getMeasuredWidth()==expected,"Literal capped slider width min(620 dp, available)");
   require(errors,x.seek.getHeight()==dp(64)&&x.seek.getMax()==200&&x.seek.getProgress()==0&&"Photo zoom".contentEquals(x.seek.getContentDescription()),"Slider height, range, value and accessible label unchanged");
   List<String> labels=Arrays.asList("Show whole photo","Cancel","Save position");ViewGroup buttons=(ViewGroup)x.editor.getChildAt(2);boolean buttonsSame=buttons.getChildCount()==3;for(int i=0;i<3&&buttonsSame;i++)buttonsSame=labels.get(i).contentEquals(((TextView)buttons.getChildAt(i)).getText());require(errors,buttonsSame,"Existing editor actions retained without invocation");
   result.put("rootWidthPx",width).put("rootHeightPx",x.controls.getHeight()).put("paddingLeftPx",x.controls.getPaddingLeft()).put("paddingRightPx",x.controls.getPaddingRight()).put("editorLeftPx",x.editor.getLeft()).put("editorRightPx",x.editor.getRight()).put("editorWidthPx",x.editor.getWidth()).put("seekLeftPx",x.seek.getLeft()).put("seekRightPx",x.seek.getRight()).put("seekWidthPx",x.seek.getWidth()).put("expectedSeekWidthPx",expected).put("fullyContained",contained).put("wide620Control",widthDp==800&&x.seek.getWidth()==dp(620)).put("callbacks",callbacks);
   // Only fixture-owned visibility changes: no Adjust/Cancel/Save/controller action is invoked.
   x.editor.setVisibility(View.GONE);x.row.setVisibility(View.VISIBLE);layout(x.controls,width);require(errors,x.editor.getVisibility()==View.GONE&&x.row.getVisibility()==View.VISIBLE,"Editor hides and normal row remains owned");
   x.editor.setVisibility(View.VISIBLE);x.row.setVisibility(View.GONE);layout(x.controls,width);require(errors,x.seek.getWidth()==expected,"Repeated visible editor retains expected width");
   require(errors,((Number)budgetType.getMethod("used").invoke(x.budget)).longValue()==0&&((Number)stageType.getMethod("getPhotoCount").invoke(x.stage)).intValue()==0,"No bitmap/photo admission");
  }catch(Throwable t){if(!initialized){setupError=describe(t);setupFailures++;}else errors.add("layout:"+describe(t));}finally{if(x!=null){clean=x.retire();active.remove(x);require(errors,clean,"Actual Stage and controls stopped, empty budget retained");}}
  require(errors,context.unexpected==0&&callbacks==0,"No preference/resolver/service/app action or callback");boolean pass=setupError==null&&errors.isEmpty();if(!pass)failures++;
  cases.put(result.put("passed",pass).put("errors",new JSONArray(errors)).put("setupError",setupError==null?JSONObject.NULL:setupError).put("retired",clean).put("unexpectedContextOperations",context.unexpected).put("actualConstructors",initialized));
 }
 private final class Setup {
  final Object budget,stage;final LinearLayout controls,editor,row;final SeekBar seek;boolean done;
  Setup()throws Exception{
   budget=budgetType.getConstructor().newInstance();stage=stageType.getConstructor(Context.class,budgetType).newInstance(context,budget);stageType.getMethod("setHostActive",boolean.class).invoke(stage,false);
   Class<?> f0=loader.loadClass("kotlin.jvm.functions.Function0"),f1=loader.loadClass("kotlin.jvm.functions.Function1"),f3=loader.loadClass("kotlin.jvm.functions.Function3");Object no0=function(f0,false),no1=function(f1,false),no3=function(f3,false),mode=function(f0,true);
   controls=(LinearLayout)controlsType.getConstructor(Context.class,stageType,f0,f0,f1,f0,f3,f0,f0).newInstance(context,stage,no0,no0,no1,no0,no3,no0,mode);
   editor=(LinearLayout)field(controls,"editor");row=(LinearLayout)field(controls,"row");check(editor.getChildCount()==3&&editor.getChildAt(1) instanceof SeekBar,"Actual editor SeekBar admission");seek=(SeekBar)editor.getChildAt(1);
  }
  boolean retire(){if(done)return true;done=true;boolean ok=true;try{controlsType.getMethod("stop").invoke(controls);}catch(Throwable t){ok=false;}try{stageType.getMethod("stop").invoke(stage);ok&=((Number)budgetType.getMethod("used").invoke(budget)).longValue()==0;}catch(Throwable t){ok=false;}if(ok)retired++;return ok;}
 }
 private Object function(Class<?> type,boolean mode){return Proxy.newProxyInstance(loader,new Class<?>[]{type},(p,m,a)->{if(m.getName().equals("invoke")){callbacks++;return mode?"smart":unit;}if(m.getName().equals("toString"))return "OwnedNoAction";if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==a[0];throw new IllegalStateException("Unexpected adapter method "+m);});}
 private static final class OwnedContext extends ContextWrapper {
  int unexpected;OwnedContext(Context c){super(c);}private AssertionError deny(String op){unexpected++;return new AssertionError("No generated layout context operation admitted: "+op);}
  @Override public Context getApplicationContext(){return this;}
  @Override public SharedPreferences getSharedPreferences(String n,int m){throw deny("SharedPreferences");}
  @Override public ContentResolver getContentResolver(){throw deny("ContentResolver");}
  @Override public Object getSystemService(String n){throw deny("systemService:"+n);}
  @Override public String getSystemServiceName(Class<?> c){throw deny("typedSystemService:"+c.getName());}
  @Override public void startActivity(Intent i){throw deny("startActivity");}
  @Override public void startActivity(Intent i,Bundle b){throw deny("startActivityBundle");}
 }
 private int dp(int n){return (int)(n*context.getResources().getDisplayMetrics().density);}
 private static void layout(View v,int width){v.forceLayout();v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));v.layout(0,0,width,v.getMeasuredHeight());}
 private static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 private static void require(List<String> errors,boolean ok,String why){if(!ok)errors.add(why);}private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 private static String describe(Throwable t){while(t instanceof InvocationTargetException&&t.getCause()!=null)t=t.getCause();return t.getClass().getName()+":"+t.getMessage();}
 private static String digest(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
 private synchronized void finishReport(String error){if(closed)return;closed=true;owner.removeCallbacksAndMessages(null);boolean cleanup=true;for(Setup x:new ArrayList<>(active))cleanup&=x.retire();active.clear();try{JSONObject r=new JSONObject().put("ok",error==null&&cases.length()==3&&failures==0&&setupFailures==0&&retired==3&&cleanup&&origin&&context.unexpected==0&&callbacks==0).put("error",error==null?JSONObject.NULL:error).put("tests",cases.length()).put("failures",failures).put("setupFailures",setupFailures).put("cases",cases).put("actualEmbeddedOrigin",origin).put("actualControlsConstructor",origin).put("sourceSha256",source==null?JSONObject.NULL:digest(source)).put("sourceBytes",source==null?0:source.length()).put("sdk",Build.VERSION.SDK_INT).put("density",getResources().getDisplayMetrics().density).put("fixturePackage",getPackageName()).put("internetPermission",checkSelfPermission("android.permission.INTERNET")==0).put("unexpectedContextOperations",context.unexpected).put("callbacks",callbacks).put("retired",retired).put("cleanup",cleanup).put("scope","Actual detached debug Stage and SlideshowViewerControls constructors plus real Android EXACT-width/UNSPEC-height layout with generated resources only. Editor visibility is fixture-set; no Adjust/Save/Cancel invocation, mounted UI, image/source/settings/controller startup, button horizontal-fit policy, hardware raster, optimized/API28 or room visual claim.");try(OutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),"report.json"))){out.write(r.toString(2).getBytes("UTF-8"));}}catch(Throwable ignored){}finish();}
 @Override protected void onDestroy(){if(!closed)finishReport("owner_destroyed");super.onDestroy();}
}
