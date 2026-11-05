/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.webtouchfixture;
import android.app.Activity;
import android.content.*;
import android.content.res.*;
import android.os.*;
import android.view.*;
import android.webkit.WebView;
import android.util.Base64;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
public final class MainActivity extends Activity {
 private final Handler handler=new Handler(Looper.getMainLooper());
 private Object controller; private Method stop,onTouch; private WebView web;
 private File output; private boolean closed; private int attempts,exits; private String phase;
 private final CountingExecutor worker=new CountingExecutor(); private final JSONObject results=new JSONObject();
 private static final class CountingExecutor extends AbstractExecutorService {
  int submissions; boolean closed;
  public void execute(Runnable task){submissions++;} // NEVER run a provider body.
  public void shutdown(){closed=true;} public List<Runnable> shutdownNow(){closed=true;return Collections.emptyList();}
  public boolean isShutdown(){return closed;} public boolean isTerminated(){return closed;}
  public boolean awaitTermination(long n,TimeUnit u){return closed;}
 }
 @Override public void onCreate(Bundle saved){super.onCreate(saved);phase=getIntent().getStringExtra("phase");
  try {output=getExternalFilesDir(null);if(output==null)throw new IllegalStateException("owned output");
            final Context app = createPackageContext("com.immortal.launcher.debug",
                    CONTEXT_IGNORE_SECURITY);
            final Resources.Theme theme = app.getResources().newTheme();
            theme.applyStyle(android.R.style.Theme_Material_NoActionBar, true);
            Context isolated = new ContextWrapper(this) {
                @Override public Resources getResources() { return app.getResources(); }
                @Override public AssetManager getAssets() { return app.getAssets(); }
                @Override public Resources.Theme getTheme() { return theme; }
                @Override public Context getApplicationContext() { return MainActivity.this.getApplicationContext(); }
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return MainActivity.this.getSharedPreferences(name, mode);
                }
                @Override public File getCacheDir() { return MainActivity.this.getCacheDir(); }
                @Override public File getFilesDir() { return MainActivity.this.getFilesDir(); }
                @Override public File getCodeCacheDir() { return MainActivity.this.getCodeCacheDir(); }
            };
            File apk = new File(getCodeCacheDir(), "generated-source.apk");
            if (apk.exists() && !apk.delete()) throw new IllegalStateException("Owned APK replacement");
            try (InputStream in = getAssets().open("source.apk");
                    FileOutputStream out = new FileOutputStream(apk)) {
                byte[] bytes = new byte[65536];
                int n;
                while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            }
            if (!apk.setReadOnly()) throw new IllegalStateException("Owned DEX read-only");
            ClassLoader loader = new DexClassLoader(apk.getAbsolutePath(),
                    getCodeCacheDir().getAbsolutePath(), null, getClassLoader());
            Class<?> type = loader.loadClass("com.immortal.launcher.PhotoFrameController");
            controller = type.getConstructor(Context.class, boolean.class, String.class,
                    String.class, long.class, long.class, boolean.class, boolean.class,
                    boolean.class).newInstance(isolated, false, "", "nature",
                    1800000L, 1800000L, false, false, false);
            stop = type.getMethod("stop");

   if(!getSharedPreferences("immortal_screensaver",MODE_PRIVATE).edit().clear().putString("mode","classic").commit())throw new IllegalStateException("owned Classic setup");
   Class<?> config=loader.loadClass("com.immortal.launcher.ScreensaverConfig");
   Object snapshot=config.getMethod("load",Context.class).invoke(config.getField("INSTANCE").get(null),isolated);
   Field settings=type.getDeclaredField("settings");settings.setAccessible(true);settings.set(controller,snapshot);
   if(!"classic".equals(snapshot.getClass().getMethod("getMode").invoke(snapshot)))throw new IllegalStateException("actual Config.load mode");
   onTouch=type.getMethod("onTouch",MotionEvent.class);
   Field io=type.getDeclaredField("io");io.setAccessible(true);io.set(controller,worker);
   Method exit=Arrays.stream(type.getMethods()).filter(m->m.getName().equals("setOnExit")).findFirst().get();
   Class<?> callback=exit.getParameterTypes()[0];
   exit.invoke(controller,Proxy.newProxyInstance(loader,new Class<?>[]{callback},(proxy,m,args)->{if(m.getName().equals("invoke"))exits++;return null;}));
   View root=(View)type.getMethod("getView").invoke(controller);setContentView(root);
   String html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
    +"<meta http-equiv='Content-Security-Policy' content=\"default-src 'none';script-src 'unsafe-inline';style-src 'unsafe-inline'\">"
    +"<style>html,body{margin:0;width:100%;height:100%;background:#173f42;color:white;font:32px sans-serif}</style></head>"
    +"<body>GENERATED WEBPAGE SWIPE CHECK<script>window.counts={start:0,move:0,end:0};"
    +"['touchstart','touchmove','touchend'].forEach(function(t){document.addEventListener(t,function(e){counts[t.slice(5)]++;e.preventDefault();},{passive:false});});</script></body></html>";
   Method start=type.getDeclaredMethod("startWebPage",String.class);start.setAccessible(true);
   start.invoke(controller,"data:text/html;base64,"+Base64.encodeToString(html.getBytes("UTF-8"),Base64.NO_WRAP));
   web=find(root);if(web==null)throw new IllegalStateException("missing actual WebView");handler.postDelayed(this::ready,200);
  }catch(Throwable e){finishResult(e);}
 }
 private WebView find(View v){if(v instanceof WebView)return (WebView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){WebView w=find(g.getChildAt(i));if(w!=null)return w;}}return null;}
 private void ready(){if(++attempts>50){finishResult(new IllegalStateException("ready timeout"));return;}
  web.evaluateJavascript("typeof counts==='object'&&document.readyState==='complete'",v->{if("true".equals(v)&&web.getWidth()>0)swipe(false,()->capture("controllerSwipe",()->swipe(true,()->capture("directSwipe",this::tap))));else handler.postDelayed(this::ready,200);});}
 private void event(boolean direct,int action,float x,long down){MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,400,0);try{if(direct)web.dispatchTouchEvent(e);else onTouch.invoke(controller,e);}catch(Throwable t){finishResult(t);}finally{e.recycle();}}
 private void swipe(boolean direct,Runnable next){long down=SystemClock.uptimeMillis();event(direct,MotionEvent.ACTION_DOWN,800,down);
  handler.postDelayed(()->event(direct,MotionEvent.ACTION_MOVE,500,down),120);
  handler.postDelayed(()->event(direct,MotionEvent.ACTION_UP,200,down),240);handler.postDelayed(next,650);}
 private void capture(String name,Runnable next){web.evaluateJavascript("JSON.stringify(counts)",v->{try{results.put(name,new JSONObject(new JSONArray("["+v+"]").getString(0)));results.put(name+"PhotoSubmissions",worker.submissions);next.run();}catch(Throwable e){finishResult(e);}});}
 private void tap(){long down=SystemClock.uptimeMillis();event(false,MotionEvent.ACTION_DOWN,500,down);handler.postDelayed(()->{event(false,MotionEvent.ACTION_UP,500,down);handler.postDelayed(this::navigation,250);},120);}
 private void navigation(){try{results.put("tapExitCalls",exits);int before=worker.submissions;
  controller.getClass().getMethod("next").invoke(controller);controller.getClass().getMethod("prev").invoke(controller);
  results.put("explicitNavigationPhotoSubmissions",worker.submissions-before);results.put("sameWebViewRetained",find((View)controller.getClass().getMethod("getView").invoke(controller))==web);finishResult(null);
 }catch(Throwable e){finishResult(e);}}
 private void finishResult(Throwable failure){if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);
  try{results.put("phase",phase);results.put("status",failure==null?"OBSERVED":"ERROR");results.put("scope","Actual debug controller private WebPage entry; real Config.load Classic fixture snapshot; owned inline HTML; counting executor never runs provider tasks; no start/startFrame");if(failure!=null){results.put("errorClass",failure.getClass().getName());results.put("error",String.valueOf(failure.getMessage()));}
   if(controller!=null&&stop!=null){stop.invoke(controller);results.put("stopReturned",true);}
   try(FileOutputStream out=new FileOutputStream(new File(output,"results.json"))){out.write(results.toString(2).getBytes("UTF-8"));}
  }catch(Throwable e){android.util.Log.e("GeneratedWebTouch","fixture report",e);}finally{finish();}}
 @Override protected void onDestroy(){if(!closed)finishResult(new IllegalStateException("destroyed before result"));super.onDestroy();}
}
