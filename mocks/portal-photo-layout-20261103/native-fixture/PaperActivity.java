/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.paperfixture;
import android.app.Activity;
import android.content.Context;
import android.os.*;
import android.webkit.*;
import android.widget.FrameLayout;
import android.view.*;
import android.graphics.*;
import org.json.*;
import java.io.*;
import java.util.*;
public final class PaperActivity extends Activity  {
  private final Handler handler=new Handler(Looper.getMainLooper());
  private WebView web;
  private Context target;
  private final JSONArray observations=new JSONArray();
  private final Map<String,byte[]> photos=new HashMap<>();
  private JSONArray deck=new JSONArray();
  private String mode="framed";
  private int attempt,phase;
  private boolean closed=false;
  private FrameLayout frame;
  private long deadline;
  private int assetReads,unknownRequests;
  public void onCreate(Bundle b) {
    super.onCreate(b);
    deadline=SystemClock.elapsedRealtime()+25000;
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(5894);
    try {
      target=createPackageContext("com.immortal.launcher.debug",Context.CONTEXT_IGNORE_SECURITY);
      int[][] dims= {
        {
          400,300
        }
        , {
          300,450
        }
        , {
          320,320
        }
        , {
          500,200
        }
        , {
          280,400
        }
        , {
          420,280
        }
      }
      ;
      for(int i=0;
      i<dims.length;
      i++) {
        Bitmap image=Bitmap.createBitmap(dims[i][0],dims[i][1],Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.rgb(170+i*10,80+i*20,65+i*20));
        Canvas c=new Canvas(image);
        Paint p=new Paint();
        int[] colors= {
          Color.GREEN,Color.BLUE,Color.YELLOW,Color.MAGENTA
        }
        ;
        for(int k=0;
        k<4;
        k++) {
          p.setColor(colors[k]);
          float x=k%2==0?0:image.getWidth()-40,y=k<2?0:image.getHeight()-40;
          c.drawRect(x,y,x+40,y+40,p);
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.PNG,100,out);
        image.recycle();
        photos.put("/photo/"+i+".png",out.toByteArray());
        deck.put(new JSONObject().put("id",i).put("seed","owned-"+i).put("w",dims[i][0]).put("h",dims[i][1]).put("url","https://paper.fixture/photo/"+i+".png"));
      }
      frame=new FrameLayout(this);
      setContentView(frame);
      web=new WebView(this);
      web.getSettings().setJavaScriptEnabled(true);
      web.getSettings().setAllowFileAccess(false);
      web.getSettings().setAllowContentAccess(false);
      web.setWebViewClient(new WebViewClient() {
        public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r) {
          String path=r.getUrl().getPath();
          try {
            byte[] data=photos.get(path);
            if(data!=null)return new WebResourceResponse("image/png",null,new ByteArrayInputStream(data));
            if(r.getUrl().getHost().equals("paper.fixture")&&path.matches("/(styles\\.css|lib-engine\\.js|modes\\.js|mode-impls\\.js|photo-doodles\\.svg)")) {
              assetReads++;
              String mime=path.endsWith(".css")?"text/css":path.endsWith(".svg")?"image/svg+xml":"text/javascript";
              return new WebResourceResponse(mime,"UTF-8",target.getAssets().open("screensaver"+path));
            }
          }
          catch(Exception e) {
          }
          unknownRequests++;
          return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
        }
      }
      );
      frame.addView(web,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
      start();
    }
    catch(Throwable e) {
      finishReport("setup_failed:"+e.getClass().getSimpleName());
    }
  }
  private void start() {
    attempt=0;
    phase=0;
    web.loadDataWithBaseURL("https://paper.fixture/",page(),"text/html","UTF-8",null);
    handler.postDelayed(this::poll,900);
  }
  private String page() {
    return "<!doctype html><html><meta name='viewport' content='width=device-width,initial-scale=1'><link rel='stylesheet' href='styles.css'><body><div id='stage'><div id='owned' class='ss-beat on first-scene'></div></div><script>var PHOTOS="+deck+";function aspect(p){return p.w/p.h}function photoUrl(p){return p.url}</script><script src='lib-engine.js'></script><script src='modes.js'></script><script src='mode-impls.js'></script><script>var ready=false,cleanup=null;requestAnimationFrame(function(){var root=document.getElementById('owned');var ctx={root:root,deck:PHOTOS,cursor:{i:0},W:root.clientWidth,H:root.clientHeight,url:photoUrl,photoMotion:false,photoMs:600000,microMs:600000};cleanup=SS.MODES['"+mode+"'].run(ctx,PHOTOS);cleanup.ready.then(function(ok){ready=ok===true})});function state(){function rect(e){var r=e.getBoundingClientRect();return {x:r.left,y:r.top,w:r.width,h:r.height}}var selector='"+(mode.equals("framed")?".ss-art.on .ss-print":".ss-mosaic-pane.on .mosaic-photo")+"';return {ready:ready,mode:'"+mode+"',width:innerWidth,height:innerHeight,dpr:devicePixelRatio,root:rect(document.getElementById('owned')),images:Array.from(document.querySelectorAll(selector)).map(function(e){var p=PHOTOS.filter(function(p){return e.style.backgroundImage.indexOf(p.url)>=0})[0];return {rect:rect(e),frame:rect(e.parentElement),ratio:p?p.w/p.h:0,contain:getComputedStyle(e).backgroundSize==='contain',still:getComputedStyle(e).animationName==='none'}}),cells:Array.from(document.querySelectorAll('.mosaic-cell')).map(function(e){return {border:getComputedStyle(e).borderTopWidth,background:getComputedStyle(e).backgroundColor}}),background:getComputedStyle(document.body).backgroundColor}}</script></body></html>";
  }
  private void poll() {
    if(closed)return;
    if(SystemClock.elapsedRealtime()>deadline||attempt++>35) {
      finishReport("readiness_timeout");
      return;
    }
    web.evaluateJavascript("typeof state==='function'?JSON.stringify(state()):null",raw-> {
      try {
        if(raw.equals("null")) {
          handler.postDelayed(this::poll,200);
          return;
        }
        String text=(String)new JSONTokener(raw).nextValue();
        JSONObject value=new JSONObject(text);
        if(!value.optBoolean("ready")) {
          handler.postDelayed(this::poll,200);
          return;
        }
        value.put("phase",phase).put("nativeWidth",web.getWidth()).put("nativeHeight",web.getHeight());
        observations.put(value);
        if(phase==0) {
          capture(mode);
          phase=1;
          FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)web.getLayoutParams();
          lp.width=(int)(web.getHeight()*.62);
          web.setLayoutParams(lp);
          handler.postDelayed(()-> {
            web.evaluateJavascript("dispatchEvent(new Event('resize'))",null);
            handler.postDelayed(this::poll,250);
          }
          ,150);
        }
        else if(phase==1) {
          phase=2;
          FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)web.getLayoutParams();
          lp.width=-1;
          web.setLayoutParams(lp);
          handler.postDelayed(()-> {
            web.evaluateJavascript("dispatchEvent(new Event('resize'))",null);
            handler.postDelayed(this::poll,250);
          }
          ,150);
        }
        else if(mode.equals("framed")) {
          mode="mosaic";
          web.evaluateJavascript("cleanup();",x->start());
        }
        else finishReport(null);
      }
      catch(Throwable e) {
        finishReport("observation_failed:"+e.getClass().getSimpleName());
      }
    }
    );
  }
  private void capture(String name) {
    try {
      Bitmap bitmap=Bitmap.createBitmap(getWindow().getDecorView().getWidth(),getWindow().getDecorView().getHeight(),Bitmap.Config.ARGB_8888);
      PixelCopy.request(getWindow(),bitmap,result-> {
        try {
          if(result==PixelCopy.SUCCESS) {
            try(FileOutputStream out=new FileOutputStream(new File(getExternalFilesDir(null),name+".png"))) {
              bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
            }
          }
        }
        catch(Exception e) {
        }
        finally {
          bitmap.recycle();
        }
      }
      ,handler);
    }
    catch(Throwable e) {
    }
  }
  private void finishReport(String error) {
    if(closed)return;
    closed=true;
    try {
      JSONObject out=new JSONObject().put("error",error==null?JSONObject.NULL:error).put("observations",observations).put("assetReads",assetReads).put("unknownRequests",unknownRequests).put("targetPackage","com.immortal.launcher.debug");
      try(FileOutputStream f=new FileOutputStream(new File(getExternalFilesDir(null),"report.json"))) {
        f.write(out.toString(2).getBytes("UTF-8"));
      }
    }
    catch(Exception e) {
    }
    handler.removeCallbacksAndMessages(null);
    if(web!=null) {
      frame.removeView(web);
      web.stopLoading();
      web.destroy();
      web=null;
    }
    finish();
  }
  protected void onDestroy() {
    if(!closed)finishReport("owner_destroyed");
    super.onDestroy();
  }
}
