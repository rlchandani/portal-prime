/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.graphitefixture;
import android.app.Activity;
import android.os.*;
import android.webkit.*;
import android.widget.FrameLayout;
import android.view.*;
import android.graphics.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class GraphiteActivity extends Activity {
 private final Handler h=new Handler(Looper.getMainLooper());
 private WebView web; private ZipFile apk; private FrameLayout frame;
 private final Map<String,byte[]> photos=new HashMap<>();
 private final JSONArray observations=new JSONArray();
 private final JSONArray deniedRequestDiagnostics=new JSONArray();
 private final AtomicInteger svgReads=new AtomicInteger(),unknown=new AtomicInteger();
 private JSONArray deck; private int scene=0,attempt=0;private boolean closed=false;private long deadline;
 private String mode(){return scene<2?"kenBurns":"push";}
 public void onCreate(Bundle b){super.onCreate(b);deadline=SystemClock.elapsedRealtime()+40000;
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().getDecorView().setSystemUiVisibility(5894);
  try {File file=new File(getCacheDir(),"source.apk");try(InputStream in=getAssets().open("source.apk");OutputStream o=new FileOutputStream(file)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)o.write(buf,0,n);}apk=new ZipFile(file);
   deck=new JSONArray();int[][] dims={{800,500},{480,800}};
   for(int i=0;i<2;i++){Bitmap im=Bitmap.createBitmap(dims[i][0],dims[i][1],Bitmap.Config.ARGB_8888);im.eraseColor(Color.rgb(92,125,143));Canvas c=new Canvas(im);Paint p=new Paint();int[] colors={Color.GREEN,Color.BLUE,Color.YELLOW,Color.MAGENTA};for(int k=0;k<4;k++){p.setColor(colors[k]);int x=k%2==0?0:im.getWidth()-60,y=k<2?0:im.getHeight()-60;c.drawRect(x,y,x+60,y+60,p);}ByteArrayOutputStream bytes=new ByteArrayOutputStream();im.compress(Bitmap.CompressFormat.PNG,100,bytes);im.recycle();photos.put("/photo/"+i+".png",bytes.toByteArray());deck.put(new JSONObject().put("id",i).put("seed","generated-"+i).put("w",dims[i][0]).put("h",dims[i][1]).put("url","https://graphite.fixture/photo/"+i+".png"));}
   frame=new FrameLayout(this);setContentView(frame);web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
   web.setWebViewClient(new WebViewClient(){public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
    // loadDataWithBaseURL owns this data main frame; it is not a resource request.
    if(r.isForMainFrame() && "data".equals(r.getUrl().getScheme())) return null;
    String path=r.getUrl().getPath();
    if("graphite.fixture".equals(r.getUrl().getHost()) && "/favicon.ico".equals(path))
      return new WebResourceResponse("image/x-icon","UTF-8",404,"Not Found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));Exception requestError=null;try{if("graphite.fixture".equals(r.getUrl().getHost())){byte[] data=photos.get(path);if(data!=null)return response("image/png",data);if(path.matches("/(styles\\.css|lib-engine\\.js|modes\\.js|mode-impls\\.js|photo-doodles-dark\\.svg)")){ZipEntry e=apk.getEntry("assets/screensaver"+path);if(e==null)throw new IOException("missing owned asset");try(InputStream in=apk.getInputStream(e);ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] buf=new byte[16384];int n;while((n=in.read(buf))!=-1)o.write(buf,0,n);if(path.endsWith("dark.svg"))svgReads.incrementAndGet();return response(path.endsWith(".svg")?"image/svg+xml":path.endsWith(".css")?"text/css":"text/javascript",o.toByteArray());}}}}catch(Exception e){requestError=e;}unknown.incrementAndGet();recordDeniedRequest(r,requestError);return response("text/plain",new byte[0]);}});
   frame.addView(web,new FrameLayout.LayoutParams(-1,-1));start();
  }catch(Throwable t){finishReport("setup:"+t.getClass().getSimpleName());}
 }
 private static String bounded(String value){if(value==null)return "";return value.length()>256?value.substring(0,256):value;}
 private void recordDeniedRequest(WebResourceRequest request,Exception error){synchronized(deniedRequestDiagnostics){if(deniedRequestDiagnostics.length()>=16)return;try{JSONObject diagnostic=new JSONObject().put("scheme",bounded(request.getUrl().getScheme())).put("host",bounded(request.getUrl().getHost())).put("path",bounded(request.getUrl().getPath())).put("mainFrame",request.isForMainFrame()).put("method",bounded(request.getMethod())).put("exception",error==null?JSONObject.NULL:error.getClass().getName()).put("exceptionMessage",error==null?JSONObject.NULL:bounded(error.getMessage()));deniedRequestDiagnostics.put(diagnostic);}catch(JSONException ignored){}}}
 private JSONArray deniedRequestSnapshot(){synchronized(deniedRequestDiagnostics){try{return new JSONArray(deniedRequestDiagnostics.toString());}catch(JSONException ignored){return new JSONArray();}}}
 private WebResourceResponse response(String mime,byte[] b){return new WebResourceResponse(mime,"UTF-8",new ByteArrayInputStream(b));}
 private void start(){attempt=0;web.loadDataWithBaseURL("https://graphite.fixture/",page(),"text/html","UTF-8",null);h.postDelayed(this::poll,900);}
 private String page(){return "<!doctype html><html><meta name='viewport' content='width=device-width,initial-scale=1'><link rel='stylesheet' href='styles.css'><body><div id='stage'><div id='owned' class='ss-beat on first-scene'></div></div><script>var PHOTOS="+deck+";function aspect(p){return p.w/p.h}function photoUrl(p){return p.url}</script><script src='lib-engine.js'></script><script src='modes.js'></script><script src='mode-impls.js'></script><script>var ready=false,cleanup=null,svgDecoded=false;var svg=new Image();svg.onload=function(){svgDecoded=svg.naturalWidth===480&&svg.naturalHeight===360};svg.src='photo-doodles-dark.svg';requestAnimationFrame(function(){var root=document.getElementById('owned'),ctx={root:root,deck:PHOTOS,cursor:{i:"+(scene%2)+"},W:root.clientWidth,H:root.clientHeight,url:photoUrl,photoMotion:false,photoMs:600000,microMs:600000,motionMs:600000};cleanup=SS.MODES['"+mode()+"'].run(ctx,PHOTOS);cleanup.ready.then(function(ok){ready=ok===true})});function state(){var layer=document.querySelector('.ss-layer.on'),hero=layer&&layer.querySelector('.ss-hero');if(!layer||!hero)return {ready:false};var r=hero.getBoundingClientRect(),p=PHOTOS["+(scene%2)+"],fit=Math.min(r.width/p.w,r.height/p.h);var cs=getComputedStyle(layer);return {ready:ready,svgDecoded:svgDecoded,background:cs.backgroundImage,size:cs.backgroundSize,repeat:cs.backgroundRepeat,color:cs.backgroundColor,animation:cs.animationName,contain:getComputedStyle(hero).backgroundSize==='contain',heroAnimation:getComputedStyle(hero).animationName,photoBackground:hero.style.backgroundImage,fit:{x:r.x+(r.width-p.w*fit)/2,y:r.y+(r.height-p.h*fit)/2,w:p.w*fit,h:p.h*fit},dpr:devicePixelRatio,width:innerWidth,height:innerHeight}};</script></body></html>";}
 private void poll(){if(closed)return;if(SystemClock.elapsedRealtime()>deadline||attempt++>60){finishReport("readiness_timeout");return;}web.evaluateJavascript("typeof state==='function'?JSON.stringify(state()):null",raw->{try{if(raw.equals("null")){h.postDelayed(this::poll,150);return;}JSONObject v=new JSONObject((String)new JSONTokener(raw).nextValue());if(!v.optBoolean("ready")||!v.optBoolean("svgDecoded")){h.postDelayed(this::poll,150);return;}
  boolean good=v.getBoolean("contain")&&v.getString("background").contains("photo-doodles-dark.svg")&&v.getString("size").equals("480px 360px")&&v.getString("repeat").equals("repeat")&&v.getString("animation").equals("none")&&v.getString("heroAnimation").equals("none")&&v.getString("photoBackground").contains("/photo/"+(scene%2)+".png")&&svgReads.get()>0&&unknown.get()==0;
  if(!good){v.put("assertionFailed",true);observations.put(v);finishReport("scene_assertion_failed");return;}v.put("mode",mode()).put("orientation",scene%2==0?"landscape":"portrait").put("svgRequests",svgReads.get());capture(v);
 }catch(Throwable t){finishReport("observe:"+t.getClass().getSimpleName());}});}
 private void capture(JSONObject v){try{Bitmap bitmap=Bitmap.createBitmap(getWindow().getDecorView().getWidth(),getWindow().getDecorView().getHeight(),Bitmap.Config.ARGB_8888);PixelCopy.request(getWindow(),bitmap,result->{try{if(result!=PixelCopy.SUCCESS)throw new IOException("pixelcopy "+result);JSONObject fit=v.getJSONObject("fit");double scale=v.getDouble("dpr");int[] at=new int[2];web.getLocationInWindow(at);int varying=0,sampled=0;Map<Integer,Integer> histogram=new HashMap<>();for(int y=4;y<web.getHeight()-4;y+=2)for(int x=4;x<web.getWidth()-4;x+=2){double cssx=x/scale,cssy=y/scale;if(cssx>fit.getDouble("x")-2&&cssx<fit.getDouble("x")+fit.getDouble("w")+2&&cssy>fit.getDouble("y")-2&&cssy<fit.getDouble("y")+fit.getDouble("h")+2)continue;sampled++;int color=bitmap.getPixel(at[0]+x,at[1]+y);histogram.put(color,histogram.containsKey(color)?histogram.get(color)+1:1);}int solid=0,dominant=0;for(Map.Entry<Integer,Integer> e:histogram.entrySet())if(e.getValue()>dominant){dominant=e.getValue();solid=e.getKey();}for(Map.Entry<Integer,Integer> e:histogram.entrySet())if(Math.abs(Color.red(e.getKey())-Color.red(solid))+Math.abs(Color.green(e.getKey())-Color.green(solid))+Math.abs(Color.blue(e.getKey())-Color.blue(solid))>5)varying+=e.getValue();
 String name=mode()+"-"+(scene%2==0?"landscape":"portrait")+".png";try(OutputStream o=new FileOutputStream(new File(getExternalFilesDir(null),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,o);}v.put("screenshot",name).put("pixelCopy",true).put("marginSamples",sampled).put("nonSolidMarginSamples",varying).put("dominantMarginColor",solid).put("dominantMarginSamples",dominant);observations.put(v);if(varying<100||dominant<sampled*.70)throw new IOException("no native margin marks against stable dominant color");scene++;if(scene<4)web.evaluateJavascript("cleanup();",x->start());else finishReport(null);
 }catch(Throwable t){finishReport("capture:"+t.getClass().getSimpleName());}finally{bitmap.recycle();}},h);}catch(Throwable t){finishReport("capture_setup:"+t.getClass().getSimpleName());}}
 private void finishReport(String error){if(closed)return;closed=true;h.removeCallbacksAndMessages(null);boolean destroyed=false;try{if(web!=null){frame.removeView(web);web.stopLoading();web.destroy();web=null;destroyed=true;}if(apk!=null){apk.close();apk=null;}JSONObject r=new JSONObject().put("ok",error==null&&observations.length()==4&&svgReads.get()>0&&unknown.get()==0).put("error",error==null?JSONObject.NULL:error).put("observations",observations).put("svgRequests",svgReads.get()).put("unknownRequests",unknown.get()).put("deniedRequestDiagnostics",deniedRequestSnapshot()).put("deniedRequestDiagnosticLimit",16).put("webViewDestroyed",destroyed).put("targetAssetSource","fixture-embedded-uninstalled-source.apk");try(OutputStream o=new FileOutputStream(new File(getExternalFilesDir(null),"report.json"))){o.write(r.toString(2).getBytes("UTF-8"));}}catch(Throwable ignored){}finish();}
 protected void onDestroy(){if(!closed)finishReport("owner_destroyed");super.onDestroy();}
}
