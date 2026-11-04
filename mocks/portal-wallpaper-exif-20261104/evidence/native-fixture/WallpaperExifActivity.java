/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.wallpaperexiffixture;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.ContentResolver;
import android.graphics.Bitmap;
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

/** Generated private JPEGs, actual APK wallpaper leaf, no real context passed to target. */
public final class WallpaperExifActivity extends Activity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final JSONObject report = new JSONObject();
  private final JSONArray tests = new JSONArray();
  private File output, inputs;
  private int retiredBitmaps;
  private boolean terminal;
  // Original JPEG patches: red, green, blue, yellow at TL,TR,BL,BR.
  private static final int[] COLORS = {0xffff3344,0xff33dd66,0xff3355ee,0xffffdd33};
  // Literal upright EXIF permutations, independently fixed before either native run.
  private static final int[][] ORDER = {
    {0,1,2,3},{1,0,3,2},{3,2,1,0},{2,3,0,1},
    {0,2,1,3},{2,0,3,1},{3,1,2,0},{1,3,0,2}
  };
  private static final int CHANNEL_TOLERANCE = 12;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    output = getExternalFilesDir(null);
    inputs = new File(getCacheDir(),"generated-wallpaper-inputs");
    handler.postDelayed(() -> complete(false,"watchdog"),30000);
    handler.post(this::runChecks);
  }

  private void runChecks() {
    TimeZone zone = TimeZone.getDefault();
    Locale locale = Locale.getDefault();
    Locale format = Locale.getDefault(Locale.Category.FORMAT);
    Locale display = Locale.getDefault(Locale.Category.DISPLAY);
    boolean ok = true;
    try {
      if (output == null || !inputs.mkdirs()) throw new AssertionError("fresh generated input directory");
      File source = new File(getCacheDir(),"source.apk");
      copyAsset("source.apk",source);
      String expected = getIntent().getStringExtra("sourceSha256");
      String actual = sha(source);
      if (expected == null || !actual.equals(expected)) throw new AssertionError("embedded APK hash");
      report.put("sourceApkSha256",actual);
      DexClassLoader loader = new DexClassLoader(source.getPath(),getCodeCacheDir().getPath(),
          null,Activity.class.getClassLoader());
      String[] origins = {"WallpaperConfig","ScreensaverConfig","LocalMedia","SlideshowDecoder"};
      JSONArray originReport = new JSONArray();
      for (String name:origins) {
        Class<?> c = loader.loadClass("com.immortal.launcher."+name);
        if (c.getClassLoader()!=loader) throw new AssertionError("class origin "+name);
        originReport.put(name);
      }
      report.put("actualApkClassOrigins",originReport);
      Class<?> wallpaper = loader.loadClass("com.immortal.launcher.WallpaperConfig");
      Object singleton = wallpaper.getField("INSTANCE").get(null);
      Method call = wallpaper.getDeclaredMethod("screensaverPhoto",Context.class);
      if (call.getReturnType()!=Bitmap.class || !Modifier.isPrivate(call.getModifiers()))
        throw new AssertionError("exact wallpaper method binding");
      call.setAccessible(true);
      for (int orientation=1;orientation<=8;orientation++) {
        JSONObject row = new JSONObject();
        row.put("name","exif-"+orientation);
        Bitmap bitmap = null;
        InertContext context = null;
        boolean passed = false;
        try {
          File folder = new File(inputs,"orientation-"+orientation);
          if (!folder.mkdir()) throw new AssertionError("fresh one-photo folder");
          File jpeg = new File(folder,"generated.jpg");
          copyAsset("generated/o"+orientation+".jpg",jpeg);
          row.put("inputSha256",sha(jpeg));
          context = new InertContext(folder.getCanonicalPath());
          bitmap = (Bitmap)call.invoke(singleton,context);
          if (bitmap == null) throw new AssertionError("actual selected local image missing");
          int width = orientation<=4 ? 360 : 240;
          int height = orientation<=4 ? 240 : 360;
          row.put("width",bitmap.getWidth()).put("height",bitmap.getHeight());
          row.put("expectedWidth",width).put("expectedHeight",height);
          // Use fractions of actual dimensions so baseline non-rotated results are observed too.
          int x0=bitmap.getWidth()/12, x1=bitmap.getWidth()-1-x0;
          int y0=bitmap.getHeight()/12, y1=bitmap.getHeight()-1-y0;
          int[][] points={{x0,y0},{x1,y0},{x0,y1},{x1,y1}};
          JSONArray pixels=new JSONArray();
          boolean corners=true;
          for (int n=0;n<4;n++) {
            int observed=bitmap.getPixel(points[n][0],points[n][1]);
            int wanted=COLORS[ORDER[orientation-1][n]];
            boolean pixelOk=near(observed,wanted);
            corners &= pixelOk;
            pixels.put(new JSONObject().put("x",points[n][0]).put("y",points[n][1])
                .put("actual",observed).put("expected",wanted).put("ok",pixelOk));
          }
          row.put("pixels",pixels).put("cornersOk",corners);
          File png=new File(output,"exif-"+orientation+".png");
          try(FileOutputStream stream=new FileOutputStream(png)) {
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream)) throw new IOException("PNG");
          }
          row.put("png",png.getName()).put("pngSha256",sha(png));
          passed=bitmap.getWidth()==width && bitmap.getHeight()==height && corners
              && context.requests==1 && context.unexpected==0 && context.fallbackAssets==0
              && context.writes==0 && context.sourceRead && context.folderRead;
          if (!passed) row.put("failure","orientation_or_containment_assertion");
        } catch(Throwable failure) {
          row.put("failure",failure.toString());
          if(failure instanceof InvocationTargetException)
            row.put("targetFailure",((InvocationTargetException)failure).getTargetException().toString());
        } finally {
          if(bitmap!=null) { bitmap.recycle(); retiredBitmaps++; }
          if(context!=null) row.put("context",context.observations());
        }
        row.put("ok",passed);
        tests.put(row);
        ok &= passed;
      }
    } catch(Throwable failure) {
      ok=false;
      try { report.put("setupFailure",failure.toString()); } catch(Exception ignored) {}
    } finally {
      TimeZone.setDefault(zone);
      Locale.setDefault(locale);
      Locale.setDefault(Locale.Category.FORMAT,format);
      Locale.setDefault(Locale.Category.DISPLAY,display);
      try {
        boolean defaultsRestored=TimeZone.getDefault().equals(zone)
            && Locale.getDefault().equals(locale)
            && Locale.getDefault(Locale.Category.FORMAT).equals(format)
            && Locale.getDefault(Locale.Category.DISPLAY).equals(display);
        boolean removed=deleteTree(inputs);
        report.put("defaultsRestored",defaultsRestored).put("generatedInputsRemoved",removed)
            .put("retiredBitmaps",retiredBitmaps).put("tests",tests)
            .put("channelTolerance",CHANNEL_TOLERANCE).put("sdk",android.os.Build.VERSION.SDK_INT);
        ok &= defaultsRestored && removed && tests.length()==8 && retiredBitmaps==8;
      } catch(Exception failure) { ok=false; }
    }
    complete(ok,null);
  }

  private void complete(boolean ok,String failure) {
    if(terminal) return;
    terminal=true;
    handler.removeCallbacksAndMessages(null);
    try {
      report.put("ok",ok).put("handlerRetired",true);
      if(failure!=null) report.put("terminalFailure",failure);
      try(FileOutputStream stream=new FileOutputStream(new File(output,"report.json"))) {
        stream.write(report.toString(2).getBytes("UTF-8"));
      }
    } catch(Exception ignored) {}
    finish();
  }
  private void copyAsset(String name,File destination) throws IOException {
    try(InputStream in=getAssets().open(name); OutputStream out=new FileOutputStream(destination)) {
      byte[] buffer=new byte[65536]; int count;
      while((count=in.read(buffer))!=-1) out.write(buffer,0,count);
    }
  }
  private static boolean near(int actual,int expected) {
    if((actual>>>24)!=255) return false;
    for(int shift:new int[]{16,8,0})
      if(Math.abs(((actual>>>shift)&255)-((expected>>>shift)&255))>CHANNEL_TOLERANCE) return false;
    return true;
  }
  private static String sha(File file) throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(InputStream in=new FileInputStream(file)) {
      byte[] buffer=new byte[65536]; int count;
      while((count=in.read(buffer))!=-1) digest.update(buffer,0,count);
    }
    StringBuilder out=new StringBuilder();
    for(byte value:digest.digest()) out.append(String.format(Locale.US,"%02x",value&255));
    return out.toString();
  }
  private static boolean deleteTree(File file) {
    if(file==null || !file.exists()) return true;
    File[] children=file.listFiles();
    if(children!=null) for(File child:children) if(!deleteTree(child)) return false;
    return file.delete();
  }
  private static final class InertContext extends ContextWrapper {
    final String folder;
    int requests,writes,unexpected,fallbackAssets;
    boolean sourceRead,folderRead;
    final SharedPreferences prefs;
    InertContext(String folder) {
      super(null); this.folder=folder;
      prefs=(SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
          new Class<?>[]{SharedPreferences.class},(proxy,method,args)-> {
            String name=method.getName();
            if(name.equals("getString")) {
              String key=(String)args[0];
              if(key.equals("source")) { sourceRead=true; return "folder"; }
              if(key.equals("folder_path")) { folderRead=true; return this.folder; }
              return args[1];
            }
            if(name.equals("getBoolean") || name.equals("getInt") || name.equals("getLong")
                || name.equals("getFloat") || name.equals("getStringSet")) return args[1];
            if(name.equals("edit")) { writes++; throw new AssertionError("no preference writes"); }
            unexpected++; throw new AssertionError("unexpected prefs operation "+name);
          });
    }
    private AssertionError denied(String what) { unexpected++; return new AssertionError(what); }
    @Override public SharedPreferences getSharedPreferences(String name,int mode) {
      requests++;
      if(!name.equals("immortal_screensaver") || mode!=Context.MODE_PRIVATE) throw denied("prefs scope");
      return prefs;
    }
    @Override public AssetManager getAssets() { fallbackAssets++; throw denied("bundled fallback"); }
    @Override public Resources getResources() { throw denied("resources"); }
    @Override public ContentResolver getContentResolver() { throw denied("resolver"); }
    @Override public Object getSystemService(String name) { throw denied("service"); }
    @Override public Context getApplicationContext() { throw denied("application context"); }
    JSONObject observations() throws Exception {
      return new JSONObject().put("preferenceRequests",requests).put("writes",writes)
          .put("unexpectedOperations",unexpected).put("fallbackAssetAttempts",fallbackAssets)
          .put("sourceRead",sourceRead).put("generatedFolderRead",folderRead);
    }
  }
}
