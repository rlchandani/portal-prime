/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.cryptofixture;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real APK fetch/HTTP/cache with Android JSON/preferences and generated transport, no network. */
public final class CryptoActivity extends Activity {
  private static final String KEY = "crypto_markets:bitcoin";
  private static final String OLD = "[{\"id\":\"bitcoin\",\"symbol\":\"btc\",\"current_price\":123.0}]";
  private static final String NEW = "[{\"id\":\"bitcoin\",\"symbol\":\"btc\",\"current_price\":456.0}]";
  private static final String OMITTED = "[{\"id\":\"ethereum\",\"symbol\":\"eth\",\"current_price\":789.0}]";
  private final JSONObject report = new JSONObject();
  private final JSONArray cases = new JSONArray();
  private String response;
  private int requests;
  private int disconnects;
  private String requestedUrl;
  private Object feed;
  private Method fetch;
  private List<Object> coins;

  public void onCreate(Bundle state) {
    super.onCreate(state);
    new Thread(() -> runFixture(), "owned-crypto-fixture").start();
  }

  private void runFixture() {
    try {
      URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler() {
        protected URLConnection openConnection(URL url) throws IOException {
          String expected = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&ids=bitcoin"
              + "&sparkline=true&price_change_percentage=24h";
          if (!url.toString().equals(expected)) throw new IOException("Unexpected generated URL");
          requests++;
          requestedUrl = url.toString();
          return new HttpURLConnection(url) {
            public int getResponseCode() throws IOException {
              if (response == null) throw new IOException("Generated offline response");
              return 200;
            }
            public InputStream getInputStream() throws IOException {
              return new ByteArrayInputStream(response.getBytes("UTF-8"));
            }
            public void connect() { throw new IllegalStateException("No network admitted"); }
            public void disconnect() { disconnects++; }
            public boolean usingProxy() { return false; }
          };
        }
      } : null);
      File source = new File(getCacheDir(), "source.apk");
      try (InputStream input = getAssets().open("source.apk");
          FileOutputStream output = new FileOutputStream(source)) {
        byte[] buffer = new byte[65536]; int n;
        while ((n = input.read(buffer)) > 0) output.write(buffer, 0, n);
      }
      ClassLoader loader = new dalvik.system.DexClassLoader(source.getAbsolutePath(),
          getCodeCacheDir().getAbsolutePath(), null, getClassLoader().getParent());
      Class<?> type = loader.loadClass("com.immortal.launcher.CryptoFeed");
      feed = type.getField("INSTANCE").get(null);
      fetch = type.getMethod("fetch", Context.class, List.class);
      Class<?> coin = loader.loadClass("com.immortal.launcher.CryptoConfig$Coin");
      coins = new ArrayList<>();
      coins.add(coin.getConstructor(String.class, String.class).newInstance("bitcoin", "XBT"));
      checkCase("html", "<html>Generated upstream error</html>", true, false);
      checkCase("truncated", "[{\"id\":\"bitcoin\",\"current_price\":456", true, false);
      checkCase("object", "{\"error\":\"Generated rate limit\"}", true, false);
      checkCase("empty-body", "", true, false);
      checkCase("first-invalid", "{\"error\":\"Generated error\"}", false, false);
      checkCase("valid-replacement", NEW, true, true);
      checkCase("valid-empty", "[]", true, true);
      checkCase("valid-omitted", OMITTED, true, true);
      checkCase("offline", null, true, false);
      freshCase();
      boolean pass = true;
      for (int i = 0; i < cases.length(); i++) pass &= cases.getJSONObject(i).getBoolean("pass");
      report.put("cases", cases).put("pass", pass).put("sdk", android.os.Build.VERSION.SDK_INT)
          .put("sourceSha256", digest(source)).put("sourceBytes", source.length())
          .put("internetPermission", checkSelfPermission("android.permission.INTERNET") == 0)
          .put("actualPublicFetch", true).put("actualHttp", true).put("androidJsonAndPreferences", true)
          .put("generatedTransportOnly", true).put("backgroundThread", true);
    } catch (Throwable error) {
      try { report.put("error", error.toString()).put("pass", false).put("cases", cases); }
      catch (Exception ignored) {}
    } finally {
      try (FileOutputStream output = new FileOutputStream(new File(getExternalFilesDir(null), "report.json"))) {
        output.write(report.toString(2).getBytes("UTF-8"));
      } catch (Exception ignored) {}
      runOnUiThread(() -> finish());
    }
  }

  private Context isolated(SharedPreferences prefs) {
    return new ContextWrapper(this) {
      public SharedPreferences getSharedPreferences(String name, int access) {
        if (!name.equals("immortal_http_cache")) throw new IllegalStateException("Unexpected preference access");
        return prefs;
      }
    };
  }

  private SharedPreferences seed(String mode, boolean prior, long stamp) {
    SharedPreferences prefs = getSharedPreferences("generated-" + mode, MODE_PRIVATE);
    SharedPreferences.Editor editor = prefs.edit().clear();
    if (prior) editor.putString("v:" + KEY, OLD).putLong("t:" + KEY, stamp);
    if (!editor.commit()) throw new IllegalStateException("Owned preference seed failed");
    return prefs;
  }

  private boolean quoteMatches(List<?> values, double price) throws Exception {
    if (values.size() != 1) return false;
    Object quote = values.get(0);
    return quote.getClass().getMethod("getSymbol").invoke(quote).equals("XBT")
        && ((Double) quote.getClass().getMethod("getUsd").invoke(quote)) == price;
  }

  private void checkCase(String mode, String body, boolean prior, boolean valid) throws Exception {
    SharedPreferences prefs = seed(mode, prior, 1L);
    response = body; requests = 0; disconnects = 0; requestedUrl = null;
    List<?> first = (List<?>) fetch.invoke(feed, isolated(prefs), coins);
    boolean firstQuotes = valid ? (body.equals(NEW) ? quoteMatches(first, 456.0) : first.isEmpty())
        : (prior ? quoteMatches(first, 123.0) : first.isEmpty());
    String expected = valid ? body : (prior ? OLD : null);
    String actual = prefs.getString("v:" + KEY, null);
    long stamp = prefs.getLong("t:" + KEY, 0L);
    boolean persisted = (expected == null ? actual == null : expected.equals(actual))
        && (valid ? stamp > 1L : stamp == (prior ? 1L : 0L));
    boolean firstTransport = requests == 1 && disconnects == 1 && requestedUrl != null;
    response = null;
    List<?> second = (List<?>) fetch.invoke(feed, isolated(prefs), coins);
    boolean secondQuotes = valid ? (body.equals(NEW) ? quoteMatches(second, 456.0) : second.isEmpty())
        : (prior ? quoteMatches(second, 123.0) : second.isEmpty());
    boolean secondTransport = requests == (valid ? 1 : 2) && disconnects == requests;
    cases.put(new JSONObject().put("mode", mode).put("firstQuotesMatch", firstQuotes)
        .put("savedRawAndStampMatch", persisted).put("firstGeneratedTransport", firstTransport)
        .put("laterOfflineQuotesMatch", secondQuotes).put("laterTransportMatch", secondTransport)
        .put("pass", firstQuotes && persisted && firstTransport && secondQuotes && secondTransport));
    prefs.edit().clear().commit();
  }

  private void freshCase() throws Exception {
    long stamp = System.currentTimeMillis();
    SharedPreferences prefs = seed("fresh", true, stamp);
    response = null; requests = 0; disconnects = 0;
    List<?> values = (List<?>) fetch.invoke(feed, isolated(prefs), coins);
    boolean pass = quoteMatches(values, 123.0) && requests == 0 && disconnects == 0
        && OLD.equals(prefs.getString("v:" + KEY, null)) && prefs.getLong("t:" + KEY, 0L) == stamp;
    cases.put(new JSONObject().put("mode", "fresh").put("pass", pass).put("requests", requests));
    prefs.edit().clear().commit();
  }

  private static String digest(File file) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new java.io.FileInputStream(file)) {
      byte[] chunk = new byte[65536]; int n;
      while ((n = input.read(chunk)) > 0) hash.update(chunk, 0, n);
    }
    StringBuilder text = new StringBuilder();
    for (byte b : hash.digest()) text.append(String.format("%02x", b & 255));
    return text.toString();
  }
}
