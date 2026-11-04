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

/** Generated-data probe of the actual debug APK's optional place parser. */
public final class CaptionNullProbe {
  private static final String OWNER = "/data/local/tmp/portal-caption-place-null-20261104-210843";
  private static final String[][] CASES = {
    {"explicitNullCityUsesLocality", "{\"city\":null,\"locality\":\"Dublin\",\"countryName\":\"Ireland\"}", "Dublin, Ireland"},
    {"explicitNullCityAndLocalityUseRegion", "{\"city\":null,\"locality\":null,\"principalSubdivision\":\"Tuscany\",\"countryName\":\"Italy\"}", "Tuscany, Italy"},
    {"explicitNullCountryDoesNotAppearAfterCity", "{\"city\":\"Paris\",\"countryName\":null}", "Paris"},
    {"allExplicitNullNamesHaveNoPlaceLabel", "{\"city\":null,\"locality\":null,\"principalSubdivision\":null,\"countryName\":null}", null},
    {"ordinaryCityBeatsLocalityAndRegion", "{\"city\":\"Paris\",\"locality\":\"Versailles\",\"principalSubdivision\":\"Ile-de-France\",\"countryName\":\"France\"}", "Paris, France"},
    {"ordinaryLocalityIsUsedWithoutCity", "{\"locality\":\"Townsville\",\"principalSubdivision\":\"Queensland\",\"countryName\":\"Australia\"}", "Townsville, Australia"},
    {"absentCityLocalityAndRegionRetainCountry", "{\"countryName\":\"France\"}", "France"},
    {"literalNullStringRemainsAPlaceName", "{\"city\":\"null\",\"countryName\":\"Ireland\"}", "null, Ireland"}
  };

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
      Class<?> caption = Class.forName("com.immortal.launcher.PhotoCaption", true, loader);
      require(caption.getClassLoader() == loader, "Parser must originate from the embedded APK");
      Method parser = null;
      for (Method method : caption.getDeclaredMethods()) {
        Class<?>[] parameters = method.getParameterTypes();
        if ((method.getName().equals("parsePlace") || method.getName().startsWith("parsePlace$"))
            && parameters.length == 1 && parameters[0] == String.class
            && method.getReturnType() == String.class) {
          require(parser == null, "Ambiguous actual parser method");
          parser = method;
        }
      }
      require(parser != null, "Actual parser method missing");
      parser.setAccessible(true);
      Object target = Modifier.isStatic(parser.getModifiers()) ? null : caption.getField("INSTANCE").get(null);
      report.put("methodName", parser.getName());
      report.put("actualParserClassOrigin", true);
      JSONArray results = new JSONArray();
      for (String[] test : CASES) {
        JSONObject result = new JSONObject();
        result.put("name", test[0]);
        result.put("expected", test[2] == null ? JSONObject.NULL : test[2]);
        try {
          Object value = parser.invoke(target, test[1]);
          require(value == null || value instanceof String, "Unexpected parser return type");
          String actual = (String) value;
          boolean pass = test[2] == null ? actual == null : test[2].equals(actual);
          result.put("actual", actual == null ? JSONObject.NULL : actual);
          result.put("pass", pass);
          result.put("fail", !pass);
          if (!pass) failures++;
        } catch (Throwable error) {
          failures++;
          result.put("actual", JSONObject.NULL);
          result.put("pass", false);
          result.put("fail", true);
          Throwable cause = error.getCause() == null ? error : error.getCause();
          result.put("exceptionType", cause.getClass().getName());
        }
        results.put(result);
      }
      report.put("cases", results);
      report.put("tests", CASES.length);
      report.put("passed", CASES.length - failures);
      report.put("failures", failures);
      report.put("setupFailures", 0);
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
