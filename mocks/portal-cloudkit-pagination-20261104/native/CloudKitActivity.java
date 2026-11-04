/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.cloudkitfixture;

import android.app.Activity;
import android.os.Bundle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual debug APK public fetch/metadata reader with Android JSON and generated HTTPS only. */
public final class CloudKitActivity extends Activity {
  private final JSONObject report = new JSONObject();
  private final JSONArray cases = new JSONArray();
  private final List<String> markers = new ArrayList<>();
  private List<String> pages;
  private int requests, disconnects;
  private Object albumSource;
  private Method fetch;

  public void onCreate(Bundle state) {
    super.onCreate(state);
    new Thread(this::runFixture, "owned-cloudkit-fixture").start();
  }

  private void runFixture() {
    try {
      URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler() {
        protected URLConnection openConnection(URL url) throws IOException {
          if (!Arrays.asList("ckdatabasews.icloud.com", "public-cloudkit.invalid").contains(url.getHost()))
            throw new IOException("Unexpected generated host");
          return new Connection(url);
        }
      } : null);
      File source = new File(getCacheDir(), "source.apk");
      try (InputStream input = getAssets().open("source.apk"); FileOutputStream output = new FileOutputStream(source)) {
        byte[] buffer = new byte[65536]; int n;
        while ((n = input.read(buffer)) > 0) output.write(buffer, 0, n);
      }
      ClassLoader loader = new dalvik.system.DexClassLoader(source.getAbsolutePath(),
          getCodeCacheDir().getAbsolutePath(), null, getClassLoader().getParent());
      Class<?> type = loader.loadClass("com.immortal.launcher.RemoteAlbum");
      albumSource = type.getField("INSTANCE").get(null);
      fetch = type.getMethod("fetch", String.class, int.class, int.class);
      check("repeated-marker", list(null, "A"), list("a", "b"),
          list(page("A", master("a", "a", true)), page("A", master("a", "a", true), master("b", "b", true))));
      check("cursor-cycle", list(null, "A", "B"), list("a", "b", "c"),
          list(page("A", master("a", "a", true)), page("B", master("b", "b", true)),
              page("A", master("a", "a", true), master("c", "c", true))));
      check("forward-overlap", list(null, "A"), list("a", "b"),
          list(page("A", master("a", "a", true)), page(null, master("a", "changed", true), master("b", "b", true))));
      check("distinct-shared-url", list((String) null), list("a", "a"),
          list(page(null, master("a", "a", true), master("a", "changed", true), master("b", "a", true))));
      check("unnamed-url", list(null, "A"), list("a", "b"),
          list(page("A", master(null, "a", true), master("", "a", true)),
              page(null, master(null, "a", true), master(null, "b", true))));
      check("unusable-first", list(null, "A"), list("a"),
          list(page("A", master("a", "missing", false)), page(null, master("a", "a", true))));
      JSONObject asset = new JSONObject().put("recordType", "CPLAsset").put("fields", new JSONObject());
      JSONObject video = master("video", "video", true);
      video.getJSONObject("fields").put("itemType", new JSONObject().put("value", "com.apple.quicktime-movie"));
      check("forward-empty-and-pairs", list(null, "A", "B"), list("a", "b"),
          list(page("A"), page("B", asset, video, master("a", "a", true)), page(null, master("b", "b", true))));
      check("malformed-later", list(null, "A"), list("a"), list(page("A", master("a", "a", true)), "{\"records\":["));
      check("transport-failure-later", list(null, "A"), list("a"), list(page("A", master("a", "a", true)), null));
      check("missing-records-later", list(null, "A"), list("a"),
          list(page("A", master("a", "a", true)), "{\"error\":\"Public fixture\"}"));
      check("empty-terminal", list((String) null), list(), list(page(null)));
      List<String> capPages = new ArrayList<>(), capMarkers = new ArrayList<>(), capPhotos = new ArrayList<>();
      capMarkers.add(null);
      for (int i = 0; i <= 50; i++) {
        capPages.add(page("marker-" + i, master("" + i, "" + i, true)));
        if (i < 50) capPhotos.add("" + i);
        if (i < 49) capMarkers.add("marker-" + i);
      }
      check("fifty-page-cap", capMarkers, capPhotos, capPages);
      int failures = 0;
      for (int i = 0; i < cases.length(); i++) if (!cases.getJSONObject(i).getBoolean("pass")) failures++;
      report.put("cases", cases).put("failures", failures).put("pass", failures == 0)
          .put("sdk", android.os.Build.VERSION.SDK_INT).put("sourceSha256", digest(source))
          .put("sourceBytes", source.length()).put("internetPermission", checkSelfPermission("android.permission.INTERNET") == 0)
          .put("actualPublicFetchAndMetadataReader", true).put("androidJson", true)
          .put("generatedTransportOnly", true).put("backgroundThread", true);
    } catch (Throwable error) {
      try { report.put("error", error.toString()).put("pass", false).put("cases", cases); } catch (Exception ignored) {}
    } finally {
      try (FileOutputStream output = new FileOutputStream(new File(getExternalFilesDir(null), "report.json"))) {
        output.write(report.toString(2).getBytes("UTF-8"));
      } catch (Exception ignored) {}
      runOnUiThread(this::finish);
    }
  }

  private void check(String name, List<String> expectedMarkers, List<String> photos, List<String> generated) throws Exception {
    pages = generated; markers.clear(); requests = 0; disconnects = 0;
    Object album = fetch.invoke(albumSource, "https://photos.icloud.com/shared/album/public-fixture", 1920, 1080);
    List<?> actual = (List<?>) album.getClass().getMethod("getPhotoUrls").invoke(album);
    List<String> expected = new ArrayList<>();
    for (String photo : photos) expected.add(image(photo));
    boolean urls = expected.equals(actual), progress = expectedMarkers.equals(markers);
    boolean title = "Public fixture".equals(album.getClass().getMethod("getTitle").invoke(album));
    boolean headers = ((Map<?, ?>) album.getClass().getMethod("getHeaders").invoke(album)).isEmpty();
    boolean closed = requests == disconnects && requests == markers.size() + 1;
    cases.put(new JSONObject().put("name", name).put("urlsMatch", urls).put("markersMatch", progress)
        .put("titleAndHeadersMatch", title && headers).put("connectionsClosed", closed)
        .put("queryRequests", markers.size()).put("photoCount", actual.size())
        .put("pass", urls && progress && title && headers && closed));
  }

  private final class Connection extends HttpURLConnection {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private boolean prepared;
    private byte[] data;
    Connection(URL url) { super(url); }
    private void prepare() throws IOException {
      if (prepared) return; prepared = true; requests++;
      try {
        if (!getRequestMethod().equals("POST")) throw new IOException("Expected POST");
        JSONObject payload = new JSONObject(output.toString("UTF-8"));
        String response;
        if (url.getPath().endsWith("/records/resolve")) {
          if (!payload.getJSONArray("shortGUIDs").getJSONObject(0).getString("value").equals("public-fixture"))
            throw new IOException("Unexpected GUID");
          response = "{\"results\":[{\"zoneID\":{\"zoneName\":\"Public Zone\",\"ownerRecordName\":\"public-owner\"},\"anonymousPublicAccess\":{\"token\":\"public-token\",\"databasePartition\":\"https://public-cloudkit.invalid:443\"},\"share\":{\"fields\":{\"cloudkit.title\":{\"value\":\"Public fixture\"}}}}]}";
        } else {
          if (!url.getHost().equals("public-cloudkit.invalid") || !url.getPath().endsWith("/records/query")
              || !url.getQuery().contains("publicAccessAuthToken=public-token")
              || payload.getInt("resultsLimit") != 200
              || !payload.getJSONObject("zoneID").getString("zoneName").equals("Public Zone")
              || !payload.getJSONObject("query").getString("recordType").equals("CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted"))
            throw new IOException("Unexpected query");
          String marker = payload.optString("continuationMarker", "");
          int index = markers.size(); markers.add(marker.isEmpty() ? null : marker);
          response = pages.get(Math.min(index, pages.size() - 1));
        }
        data = response == null ? null : response.getBytes("UTF-8");
      } catch (org.json.JSONException error) { throw new IOException(error); }
    }
    public java.io.OutputStream getOutputStream() { return output; }
    public long getContentLengthLong() { try { prepare(); } catch (IOException error) { return -1; } return data == null ? -1 : data.length; }
    public InputStream getInputStream() throws IOException {
      prepare(); if (data == null) throw new IOException("Generated transport failure");
      return new ByteArrayInputStream(data);
    }
    public void disconnect() { disconnects++; }
    public void connect() { throw new IllegalStateException("No network admitted"); }
    public boolean usingProxy() { return false; }
  }

  private static String image(String photo) { return "https://public-images.invalid/" + photo + ".jpg"; }
  private static List<String> list(String... values) { return Arrays.asList(values); }
  private static JSONObject master(String name, String photo, boolean usable) throws Exception {
    JSONObject fields = new JSONObject().put("itemType", new JSONObject().put("value", "public.jpeg"));
    if (usable) fields.put("resOriginalRes", new JSONObject().put("value", new JSONObject().put("downloadURL", image(photo))))
        .put("resOriginalWidth", new JSONObject().put("value", 1920));
    JSONObject record = new JSONObject().put("recordType", "CPLMaster").put("fields", fields);
    if (name != null) record.put("recordName", name);
    return record;
  }
  private static String page(String marker, JSONObject... records) throws Exception {
    JSONObject page = new JSONObject().put("records", new JSONArray(Arrays.asList(records)));
    if (marker != null) page.put("continuationMarker", marker);
    return page.toString();
  }
  private static String digest(File file) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new java.io.FileInputStream(file)) {
      byte[] buffer = new byte[65536]; int n;
      while ((n = input.read(buffer)) > 0) hash.update(buffer, 0, n);
    }
    StringBuilder text = new StringBuilder();
    for (byte value : hash.digest()) text.append(String.format("%02x", value & 255));
    return text.toString();
  }
}
