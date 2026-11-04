/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.memofixture;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.Arrays;

/** Actual APK AudioNote/public worker, generated files, fake stop/release; never prepares audio. */
public final class MemoActivity extends Activity {
  private static final byte[] OLD = new byte[]{11, 22, 33, 44};
  private static final byte[] NEW = new byte[]{55, 66, 77, 88, 99};
  private final JSONObject report = new JSONObject();
  private final JSONArray cases = new JSONArray();

  public void onCreate(Bundle state) {
    super.onCreate(state);
    try {
      File source = new File(getCacheDir(), "source.apk");
      try (java.io.InputStream input = getAssets().open("source.apk");
          FileOutputStream output = new FileOutputStream(source)) {
        byte[] chunk = new byte[65536]; int n;
        while ((n = input.read(chunk)) > 0) output.write(chunk, 0, n);
      }
      ClassLoader loader = new dalvik.system.DexClassLoader(source.getAbsolutePath(),
          getCodeCacheDir().getAbsolutePath(), null, getClassLoader().getParent());
      Class<?> noteType = loader.loadClass("com.immortal.launcher.AudioNote");
      for (String mode : new String[]{"valid", "first", "short", "stop", "release", "empty", "rename"}) {
        runCase(noteType, mode);
      }
      boolean pass = true;
      for (int i = 0; i < cases.length(); i++) pass &= cases.getJSONObject(i).getBoolean("pass");
      report.put("cases", cases).put("pass", pass).put("sdk", android.os.Build.VERSION.SDK_INT)
          .put("sourceSha256", digest(source)).put("sourceBytes", source.length())
          .put("recordAudioPermission", checkSelfPermission("android.permission.RECORD_AUDIO") == 0)
          .put("prepareStartAudioSourceCalls", 0).put("actualPublicStopRecording", true)
          .put("sharedWorker", true).put("generatedFilesOnly", true);
    } catch (Throwable error) {
      try { report.put("error", error.toString()).put("pass", false).put("cases", cases); }
      catch (Exception ignored) {}
    } finally {
      try (FileOutputStream output = new FileOutputStream(new File(getExternalFilesDir(null), "report.json"))) {
        output.write(report.toString(2).getBytes("UTF-8"));
      } catch (Exception ignored) {}
      finish();
    }
  }

  private void runCase(Class<?> type, String mode) throws Exception {
    final File dir = new File(getFilesDir(), "generated-" + mode);
    if (!dir.mkdir()) throw new IllegalStateException("Owned case directory already exists");
    File saved = new File(dir, "leave_note.m4a");
    if (!mode.equals("first")) write(saved, OLD);
    File unrelated = new File(dir, "other-owner.m4a.part");
    write(unrelated, OLD);
    File stage = File.createTempFile("voice-memo-", ".m4a.part", dir);
    write(stage, new byte[]{1});
    Context generated = new ContextWrapper(this) {
      public File getFilesDir() { return dir; }
      public SharedPreferences getSharedPreferences(String name, int access) {
        throw new IllegalStateException("No preference access admitted");
      }
    };
    Object note = type.getConstructor(Context.class).newInstance(generated);
    GeneratedRecorder recorder = new GeneratedRecorder(stage, mode);
    boolean restricted = mode.equals("rename");
    try {
      set(type, note, "recorder", recorder);
      set(type, note, "recordingFile", stage);
      set(type, note, "recordStarted", true);
      set(type, note, "recording", true);
      set(type, note, "recordStartMs", SystemClock.elapsedRealtime() - (mode.equals("short") ? 100L : 600L));
      if (restricted) Os.chmod(dir.getAbsolutePath(), 0500);
      boolean result = (Boolean) type.getMethod("stopRecording").invoke(note);
      boolean success = mode.equals("valid") || mode.equals("first");
      boolean savedMatches = Arrays.equals(read(saved), success ? NEW : OLD);
      boolean unrelatedMatches = Arrays.equals(read(unrelated), OLD);
      boolean detached = get(type, note, "recorder") == null && get(type, note, "recordingFile") == null;
      boolean cleanup = restricted ? stage.exists() : !stage.exists();
      boolean pass = result == success && savedMatches && unrelatedMatches && detached && cleanup
          && recorder.stopCalls == 1 && recorder.releaseCalls == 1;
      cases.put(new JSONObject().put("mode", mode).put("result", result).put("savedBytesMatch", savedMatches)
          .put("unrelatedStagePreserved", unrelatedMatches).put("handlesDetached", detached)
          .put("ownStageAbsent", !stage.exists()).put("stopCalls", recorder.stopCalls)
          .put("releaseCalls", recorder.releaseCalls).put("pass", pass));
    } finally {
      if (restricted) Os.chmod(dir.getAbsolutePath(), 0700);
      type.getMethod("release").invoke(note);
      for (File file : dir.listFiles()) file.delete();
      dir.delete();
    }
  }

  /** Allocates a native recorder handle, but never configures, prepares, starts or captures audio. */
  private static final class GeneratedRecorder extends MediaRecorder {
    final File stage;
    final String mode;
    int stopCalls, releaseCalls;
    GeneratedRecorder(File stage, String mode) { this.stage = stage; this.mode = mode; }
    public void stop() {
      stopCalls++;
      if (mode.equals("stop")) throw new IllegalStateException("Generated stop failure");
      try { write(stage, mode.equals("empty") ? new byte[0] : NEW); }
      catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    public void release() {
      releaseCalls++;
      super.release();
      if (mode.equals("release")) throw new IllegalStateException("Generated release failure");
    }
  }

  private static void set(Class<?> type, Object owner, String name, Object value) throws Exception {
    Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
  }
  private static Object get(Class<?> type, Object owner, String name) throws Exception {
    Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
  }
  private static void write(File file, byte[] bytes) throws Exception {
    try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
  }
  private static byte[] read(File file) throws Exception {
    try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
      java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
      byte[] chunk = new byte[1024]; int n;
      while ((n = input.read(chunk)) > 0) output.write(chunk, 0, n);
      return output.toByteArray();
    }
  }
  private static String digest(File file) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
      byte[] chunk = new byte[65536]; int n;
      while ((n = input.read(chunk)) > 0) hash.update(chunk, 0, n);
    }
    StringBuilder text = new StringBuilder();
    for (byte b : hash.digest()) text.append(String.format("%02x", b & 255));
    return text.toString();
  }
}
