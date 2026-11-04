/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual clock-event dispatcher with generated callbacks; no scheduler services. */
public final class ClockRearmProbe {
  private static final String OWNER = "/data/local/tmp/portal-chime-time-change-20261104-221132";
  private static final String TIME_SET = "android.intent.action.TIME_SET";
  private static final String TIMEZONE = "android.intent.action.TIMEZONE_CHANGED";
  private static final String TIME_TICK = "android.intent.action.TIME_TICK";
  private static final String[] NAMES = {
    "timeSetRearmsAllWithSnoozeRetained", "timezoneChangeRearmsAllWithSnoozeRetained",
    "timeTickDoesNotInvokeSchedulers", "alarmFailureStillCallsWakeAndChime",
    "wakeFailureStillCallsChime", "chimeFailureContainedAndLaterEventWorks"
  };

  private static Object callback(ClassLoader loader, Class<?> function, Object unit,
      java.util.List<String> calls, String label, boolean failFirst, boolean alarm) {
    final int[] invocations = {0};
    return java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{function},
        (proxy, method, arguments) -> {
          if (method.getDeclaringClass() == Object.class) {
            if (method.getName().equals("toString")) return "GeneratedClockCallback";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return arguments != null && arguments.length == 1
                && proxy == arguments[0];
          }
          require(method.getName().equals("invoke"), "Unexpected callback method");
          if (alarm) {
            require(arguments != null && arguments.length == 1 && arguments[0] instanceof Boolean,
                "Alarm callback must receive Boolean");
            calls.add("alarm:" + arguments[0]);
          } else {
            require(arguments == null || arguments.length == 0, "Zero-argument callback required");
            calls.add(label);
          }
          int index = invocations[0]++;
          if (failFirst && index == 0) throw new IllegalStateException("Generated scheduler failure");
          return unit;
        });
  }

  private static void dispatch(Method rearm, Object target, String action,
      Object alarm, Object wake, Object chime) throws Exception {
    if (rearm.getParameterTypes().length == 4) rearm.invoke(target, action, alarm, wake, chime);
    else rearm.invoke(target, action, alarm, wake);
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return out.toString();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  public static void main(String[] args) {
    JSONObject report = new JSONObject();
    int failures = 0;
    try {
      require(args.length == 3, "Expected fixture APK, owned directory, and source SHA256");
      require(args[2].matches("[a-f0-9]{64}"), "Invalid source SHA256");
      File fixture = new File(args[0]);
      File owned = new File(args[1]);
      require(fixture.isAbsolute() && owned.isAbsolute(), "Absolute owned paths required");
      require(fixture.getCanonicalPath().equals(OWNER + "/fixture.apk"), "Wrong named fixture owner path");
      require(owned.getCanonicalPath().equals(OWNER + "/owned-work"), "Wrong named work owner path");
      require(fixture.isFile(), "Fixture APK missing");
      require(owned.isDirectory() || owned.mkdir(), "Cannot create owned work directory");
      File source = new File(owned, "source.apk");
      require(source.createNewFile(), "Owned source output must not already exist");
      boolean complete = false;
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (ZipFile archive = new ZipFile(fixture)) {
        int occurrences = 0;
        Enumeration<? extends ZipEntry> entries = archive.entries();
        while (entries.hasMoreElements()) {
          if (entries.nextElement().getName().equals("assets/source.apk")) occurrences++;
        }
        require(occurrences == 1, "Expected exactly one embedded source APK");
        ZipEntry entry = archive.getEntry("assets/source.apk");
        require(!entry.isDirectory(), "Embedded source must be a file");
        long total = 0;
        try (InputStream input = archive.getInputStream(entry);
             FileOutputStream output = new FileOutputStream(source)) {
          byte[] buffer = new byte[8192];
          int count;
          while ((count = input.read(buffer)) != -1) {
            total += count;
            require(total <= 128L * 1024 * 1024, "Embedded source exceeds probe bound");
            digest.update(buffer, 0, count);
            output.write(buffer, 0, count);
          }
        }
        String sourceHash = hex(digest.digest());
        require(sourceHash.equals(args[2]), "Embedded source SHA256 mismatch");
        report.put("sourceSha256", sourceHash);
        report.put("sourceBytes", total);
        complete = true;
      } finally {
        if (!complete) source.delete();
      }
      File optimized = new File(owned, "dex-cache");
      require(optimized.mkdir(), "Owned DEX directory must be new");
      DexClassLoader loader = new DexClassLoader(source.getPath(), optimized.getPath(), null,
          ClassLoader.getSystemClassLoader());
      Class<?> dispatcher = Class.forName("com.immortal.launcher.ClockChangeAlarms", true, loader);
      require(dispatcher.getClassLoader() == loader, "Dispatcher must originate from source APK");
      Class<?> function1 = Class.forName("kotlin.jvm.functions.Function1", true, loader);
      Class<?> function0 = Class.forName("kotlin.jvm.functions.Function0", true, loader);
      Class<?> unitClass = Class.forName("kotlin.Unit", true, loader);
      Object unit = unitClass.getField("INSTANCE").get(null);
      Method rearm = null;
      for (Method method : dispatcher.getDeclaredMethods()) {
        Class<?>[] parameters = method.getParameterTypes();
        if (method.getName().equals("rearm") && method.getReturnType() == Void.TYPE
            && (parameters.length == 3 || parameters.length == 4)
            && parameters[0] == String.class && parameters[1] == function1
            && parameters[2] == function0 && (parameters.length == 3 || parameters[3] == function0)) {
          require(rearm == null, "Ambiguous exact dispatcher signature");
          rearm = method;
        }
      }
      require(rearm != null, "Expected exact three/four-parameter rearm method");
      rearm.setAccessible(true);
      Object target = Modifier.isStatic(rearm.getModifiers()) ? null : dispatcher.getField("INSTANCE").get(null);
      report.put("methodName", rearm.getName());
      report.put("parameterCount", rearm.getParameterTypes().length);
      report.put("actualDispatcherClassOrigin", true);
      JSONArray results = new JSONArray();
      for (int index = 0; index < NAMES.length; index++) {
        java.util.List<String> calls = new java.util.ArrayList<>();
        Object alarm = callback(loader, function1, unit, calls, "alarm", index == 3, true);
        Object wake = callback(loader, function0, unit, calls, "wake", index == 4, false);
        Object chime = callback(loader, function0, unit, calls, "chime", index == 5, false);
        String[] expected = index == 2 ? new String[]{} : index == 5
            ? new String[]{"alarm:false", "wake", "chime", "alarm:false", "wake", "chime"}
            : new String[]{"alarm:false", "wake", "chime"};
        Throwable escaped = null;
        try {
          String action = index == 1 ? TIMEZONE : index == 2 ? TIME_TICK : TIME_SET;
          dispatch(rearm, target, action, alarm, wake, chime);
          if (index == 5) dispatch(rearm, target, TIMEZONE, alarm, wake, chime);
        } catch (Throwable error) {
          escaped = error.getCause() == null ? error : error.getCause();
        }
        boolean pass = escaped == null && calls.equals(java.util.Arrays.asList(expected));
        JSONObject result = new JSONObject();
        result.put("name", NAMES[index]);
        result.put("expected", new JSONArray(java.util.Arrays.asList(expected)));
        result.put("actual", new JSONArray(calls));
        result.put("alarmCalls", java.util.Collections.frequency(calls, "alarm:false")
            + java.util.Collections.frequency(calls, "alarm:true"));
        result.put("wakeCalls", java.util.Collections.frequency(calls, "wake"));
        result.put("chimeCalls", java.util.Collections.frequency(calls, "chime"));
        result.put("pass", pass);
        result.put("fail", !pass);
        if (escaped != null) result.put("escapedExceptionType", escaped.getClass().getName());
        if (!pass) failures++;
        results.put(result);
      }
      report.put("cases", results);
      report.put("tests", NAMES.length);
      report.put("passed", NAMES.length - failures);
      report.put("failures", failures);
      report.put("setupFailures", 0);
      report.put("scope", "Actual dispatcher with generated callback proxies only");
      System.out.println(report.toString());
      if (failures != 0) System.exit(1);
    } catch (Throwable error) {
      try {
        report.put("tests", 0);
        report.put("failures", 0);
        report.put("setupFailures", 1);
        report.put("exceptionType", error.getClass().getName());
        report.put("message", String.valueOf(error.getMessage()));
      } catch (Exception ignored) { }
      System.out.println(report.toString());
      System.exit(1);
    }
  }
}
