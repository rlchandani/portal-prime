/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.contrastfixture;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Framework-only fixture; production classes are loaded from an exact owned debug APK copy. */
public final class ContrastActivity extends Activity {
  private JSONObject config, report = new JSONObject();
  private Object renderer;
  private Class<?> rendererType;
  private View root;
  private FrameLayout host;
  private final Handler handler = new Handler();
  private boolean retired;
  private int preferenceReads;

  public void onCreate(Bundle state) {
    super.onCreate(state);
    handler.postDelayed(() -> retire(), 15000);
    try {
      byte[] bytes;
      try (java.io.InputStream input = getAssets().open("mapping.json")) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096]; int n;
        while ((n = input.read(chunk)) > 0) out.write(chunk, 0, n);
        bytes = out.toByteArray();
      }
      config = new JSONObject(new String(bytes, "UTF-8"));
      Context target = createPackageContext("com.immortal.launcher.debug",
          Context.CONTEXT_IGNORE_SECURITY);
      Context generated = new ContextWrapper(target) {
        public SharedPreferences getSharedPreferences(String name, int mode) {
          preferenceReads++;
          // Every production preference read is redirected to this fixture's empty owned file.
          return ContrastActivity.this.getSharedPreferences("generated", Context.MODE_PRIVATE);
        }
      };
      File source = new File(getCacheDir(), "source.apk");
      try (java.io.InputStream input = getAssets().open("source.apk");
          FileOutputStream output = new FileOutputStream(source)) {
        byte[] chunk = new byte[65536]; int n;
        while ((n = input.read(chunk)) > 0) output.write(chunk, 0, n);
      }
      ClassLoader loader = new dalvik.system.DexClassLoader(source.getAbsolutePath(),
          getCodeCacheDir().getAbsolutePath(), null, getClassLoader().getParent());
      rendererType = loader.loadClass(config.getJSONObject("classes").getString("FaceRenderer"));
      for (Constructor<?> c : rendererType.getDeclaredConstructors()) {
        if (c.getParameterTypes().length == 5) {
          c.setAccessible(true);
          renderer = c.newInstance(generated, 0L, null, 6, null);
          break;
        }
      }
      if (renderer == null) throw new IllegalStateException("Renderer constructor unavailable");
      invoke("setHostActive", new Class<?>[]{boolean.class}, false);
      Class<?> faceType = loader.loadClass(config.getJSONObject("classes").getString("Face"));
      Object face = null;
      for (Constructor<?> c : faceType.getDeclaredConstructors()) {
        if (c.getParameterTypes().length == 10) {
          c.setAccessible(true);
          face = c.newInstance("generated-contrast", null, null, null, null, null, null, null, 254, null);
          break;
        }
      }
      if (face == null) throw new IllegalStateException("Face constructor unavailable");
      Object clock = field(face, "Face", "clock").get(face);
      field(clock, "ClockSpec", "color").set(clock, "#343a30");
      field(clock, "ClockSpec", "showDate").setBoolean(clock, true);
      Object weather = field(face, "Face", "weather").get(face);
      Object battery = field(face, "Face", "battery").get(face);
      field(weather, "WeatherSpec", "enabled").setBoolean(weather, false);
      field(battery, "BatterySpec", "enabled").setBoolean(battery, false);
      invoke("start", new Class<?>[]{faceType}, face);
      invoke("refreshRetainedTime", new Class<?>[]{java.util.Date.class}, new java.util.Date(1793764800000L));
      invoke("setCaption", new Class<?>[]{String.class, String.class, String.class},
          "Generated caption", "Owned fixture", null);
      root = (View) invoke("getView", new Class<?>[]{});
      host = new FrameLayout(this);
      host.setBackgroundColor(0xFFEDE9DF);
      host.addView(root);
      setContentView(host);
      invoke("setPaperPhotoSurface", new Class<?>[]{boolean.class}, true);
      handler.postDelayed(() -> inspect(), 600);
    } catch (Throwable error) { fail(error); }
  }

  private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
    Method method = rendererType.getDeclaredMethod(config.getJSONObject("members")
        .getString("FaceRenderer." + name), types);
    method.setAccessible(true);
    return method.invoke(renderer, args);
  }

  private Field field(Object owner, String type, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(config.getJSONObject("members")
        .getString(type + "." + name));
    field.setAccessible(true);
    return field;
  }

  private boolean containsCaption(View view) {
    if (view instanceof TextView &&
        ((TextView) view).getText().toString().equals("Generated caption")) return true;
    if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
      if (containsCaption(((ViewGroup) view).getChildAt(i))) return true;
    return false;
  }

  private void inspect() {
    try {
      invoke("refreshPaperPhotoSurface", new Class<?>[]{});
      LinearLayout bucket = null;
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View child = ((ViewGroup) root).getChildAt(i);
        if (child instanceof LinearLayout && containsCaption(child)) bucket = (LinearLayout) child;
      }
      if (bucket == null || bucket.getWidth() <= 0) throw new IllegalStateException("Measured bucket absent");
      Bitmap backing = Bitmap.createBitmap(bucket.getWidth(), bucket.getHeight(), Bitmap.Config.ARGB_8888);
      backing.eraseColor(0xFFEDE9DF);
      bucket.getBackground().draw(new Canvas(backing));
      int clockY = (bucket.getChildAt(0).getTop() + bucket.getChildAt(0).getBottom()) / 2;
      View caption = bucket.getChildAt(bucket.getChildCount() - 1);
      int captionY = (caption.getTop() + caption.getBottom()) / 2;
      int x = bucket.getWidth() / 2;
      int clockPixel = backing.getPixel(x, clockY);
      int captionPixel = backing.getPixel(x, captionY);
      boolean lightClock = Color.red(clockPixel) > 220 && Color.green(clockPixel) > 220;
      boolean darkCaption = Color.red(captionPixel) < 90 && Color.green(captionPixel) < 90;
      int paperPadding = bucket.getPaddingLeft();
      invoke("setPaperPhotoSurface", new Class<?>[]{boolean.class}, false);
      boolean restored = bucket.getPaddingLeft() == 0 && bucket.getBackground() == null;
      invoke("setPaperPhotoSurface", new Class<?>[]{boolean.class}, true);
      boolean readmitted = bucket.getPaddingLeft() == paperPadding && bucket.getBackground() != null;
      invoke("refreshPaperPhotoSurface", new Class<?>[]{});
      Bitmap image = Bitmap.createBitmap(host.getWidth(), host.getHeight(), Bitmap.Config.ARGB_8888);
      host.draw(new Canvas(image));
      try (FileOutputStream out = new FileOutputStream(new File(getExternalFilesDir(null), "mixed.png"))) {
        image.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      image.recycle(); backing.recycle();
      report.put("clockPixel", Integer.toHexString(clockPixel)).put("captionPixel", Integer.toHexString(captionPixel))
          .put("lightClock", lightClock).put("darkCaption", darkCaption).put("restored", restored)
          .put("readmitted", readmitted).put("paperPadding", paperPadding)
          .put("bucketWidth", bucket.getWidth()).put("bucketHeight", bucket.getHeight())
          .put("generatedPreferenceReads", preferenceReads).put("inactiveWorkers", true)
          .put("pass", lightClock && darkCaption && restored && readmitted);
      retire();
    } catch (Throwable error) { fail(error); }
  }

  private void fail(Throwable error) {
    try { report.put("error", error.toString()).put("pass", false); } catch (Exception ignored) {}
    retire();
  }

  private void retire() {
    if (retired) return;
    retired = true;
    handler.removeCallbacksAndMessages(null);
    try {
      if (renderer != null) invoke("stop", new Class<?>[]{});
      report.put("retired", true);
      try (FileOutputStream out = new FileOutputStream(new File(getExternalFilesDir(null), "report.json"))) {
        out.write(report.toString(2).getBytes("UTF-8"));
      }
    } catch (Throwable ignored) {}
    finish();
  }

  public void onDestroy() { retire(); super.onDestroy(); }
}
