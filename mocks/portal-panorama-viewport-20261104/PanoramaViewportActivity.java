/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.panoramaviewportfixture;
import android.app.Activity;
import android.os.*;
import android.webkit.*;
import android.widget.FrameLayout;
import android.graphics.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
public final class PanoramaViewportActivity extends Activity {
 private final Handler handler=new Handler(Looper.getMainLooper());
 private WebView web;private FrameLayout frame;private ZipFile source;private File sourceFile;
 private final AtomicInteger unknown=new AtomicInteger();private final Map<String,String> hashes=Collections.synchronizedMap(new TreeMap<>());
 private boolean closed;private long deadline;private JSONObject observed;
 private static final Set<String> ALLOW=new HashSet<>(Arrays.asList("styles.css","lib-engine.js","modes.js","mode-impls.js","photo-doodles-dark.svg"));
 public void onCreate(Bundle saved){super.onCreate(saved);deadline=SystemClock.elapsedRealtime()+45000;getWindow().getDecorView().setSystemUiVisibility(5894);
  try{sourceFile=new File(getCacheDir(),"source.apk");try(InputStream in=getAssets().open("source.apk");OutputStream out=new FileOutputStream(sourceFile)){copy(in,out);}source=new ZipFile(sourceFile);
   frame=new FrameLayout(this);setContentView(frame);web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
   web.setWebViewClient(new WebViewClient(){public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){String scheme=r.getUrl().getScheme(),name=r.getUrl().getLastPathSegment();if("data".equals(scheme))return null;if("panorama.fixture".equals(r.getUrl().getHost())&&"/favicon.ico".equals(r.getUrl().getPath()))return new WebResourceResponse("image/x-icon","UTF-8",404,"Not Found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));try{if("https".equals(scheme)&&"panorama.fixture".equals(r.getUrl().getHost())&&r.getUrl().getPath().equals("/"+name)&&ALLOW.contains(name)){ZipEntry entry=source.getEntry("assets/screensaver/"+name);if(entry==null)throw new IOException("missing packaged asset");byte[] bytes;try(InputStream in=source.getInputStream(entry)){bytes=read(in);}hashes.put(name,sha(bytes));return new WebResourceResponse(name.endsWith(".css")?"text/css":name.endsWith(".svg")?"image/svg+xml":"text/javascript","UTF-8",new ByteArrayInputStream(bytes));}}catch(Throwable ignored){}unknown.incrementAndGet();return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}});
   frame.addView(web,new FrameLayout.LayoutParams(-1,-1));String html;try(InputStream in=getAssets().open("fixture.html")){html=new String(read(in),"UTF-8");}web.loadDataWithBaseURL("https://panorama.fixture/",html,"text/html","UTF-8",null);handler.postDelayed(this::poll,250);
  }catch(Throwable t){finishReport("setup:"+t);}}
 private void poll(){if(closed)return;if(SystemClock.elapsedRealtime()>deadline){finishReport("readiness_timeout");return;}web.evaluateJavascript("typeof state==='function'?JSON.stringify(state()):null",raw->{try{if("null".equals(raw)){handler.postDelayed(this::poll,100);return;}observed=new JSONObject((String)new JSONTokener(raw).nextValue());if(!observed.optBoolean("complete")){handler.postDelayed(this::poll,100);return;}capture();}catch(Throwable t){finishReport("observe:"+t);}});}
 private void capture(){try{if(web.getWidth()<=0||web.getHeight()<=0)throw new IOException("unmeasured WebView");Bitmap image=Bitmap.createBitmap(web.getWidth(),web.getHeight(),Bitmap.Config.ARGB_8888);web.draw(new Canvas(image));try(OutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),"fullsize-software.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();observed.put("screenshot","fullsize-software.png").put("raster","software WebView.draw Canvas; no PixelCopy/hardware acceptance");finishReport(null);}catch(Throwable t){finishReport("capture:"+t);}}
 private void finishReport(String error){if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);boolean destroyed=false;try{if(web!=null){frame.removeView(web);web.stopLoading();web.destroy();web=null;destroyed=true;}if(source!=null){source.close();source=null;}JSONArray cases=observed==null?new JSONArray():observed.optJSONArray("observations");int fails=0;if(cases!=null)for(int i=0;i<cases.length();i++)if(!cases.getJSONObject(i).optBoolean("ok"))fails++;JSONObject r=new JSONObject().put("ok",error==null&&observed!=null&&observed.isNull("error")&&cases!=null&&cases.length()==4&&fails==0&&unknown.get()==0&&destroyed).put("error",error==null?JSONObject.NULL:error).put("tests",cases==null?new JSONArray():cases).put("failures",fails).put("state",observed==null?JSONObject.NULL:observed).put("unknownRequests",unknown.get()).put("webViewDestroyed",destroyed).put("assetHashes",new JSONObject(hashes)).put("sourceApkSha256",shaFile(sourceFile)).put("sourceApkBytes",sourceFile.length()).put("sdk",Build.VERSION.SDK_INT).put("caseCount",cases==null?0:cases.length()).put("scope","Actual packaged Panorama mode.run; generated data SVGs; virtual beat timer; real DOM layout/RAF; optional software Canvas only");try(OutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),"report.json"))){out.write(r.toString(2).getBytes("UTF-8"));}}catch(Throwable ignored){}finish();}
 protected void onDestroy(){if(!closed)finishReport("owner_destroyed");super.onDestroy();}
 private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
 private static byte[] read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();copy(in,out);return out.toByteArray();}
 private static String sha(byte[] bytes)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
 private static String shaFile(File f)throws Exception{try(InputStream in=new FileInputStream(f)){return sha(read(in));}}
}
