/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import android.os.Handler;
import android.os.HandlerThread;
import dalvik.system.DexClassLoader;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Owned app_process probe: APK objects only, generated states, no Context/session/network. */
public final class HomeFreshnessProbe {
  private static ClassLoader loader;
  private static Object hub, unit, old, replacement, paused;
  private static Class<?> stateClass, listenerClass, functionClass;
  private static Method publish, add, remove, factory;
  private static HandlerThread uiThread;
  private static Handler ui;
  private static JSONArray outcomes = new JSONArray();

  private static Object function(final InvocationHandler f) {
    return Proxy.newProxyInstance(loader, new Class<?>[]{functionClass}, f);
  }
  private static Object listener(final boolean baseline, final List<Object> rendered) throws Exception {
    if (baseline) return Proxy.newProxyInstance(loader, new Class<?>[]{listenerClass}, (p,m,a) -> {
      if (m.getName().equals("onNowPlaying")) {
        final Object captured=a[0];
        if (!ui.post(() -> rendered.add(captured))) throw new IllegalStateException("post rejected");
        return null;
      }
      return objectMethod(p,m,a);
    });
    Object post=function((p,m,a) -> {
      if (m.getName().equals("invoke")) {
        if (!ui.post((Runnable)a[0])) throw new IllegalStateException("post rejected");
        return unit;
      }
      return objectMethod(p,m,a);
    });
    Object render=function((p,m,a) -> {
      if (m.getName().equals("invoke")) { rendered.add(a[0]); return unit; }
      return objectMethod(p,m,a);
    });
    return factory.invoke(null,post,render);
  }
  private static Object objectMethod(Object p,Method m,Object[] a) {
    if(m.getName().equals("hashCode")) return System.identityHashCode(p);
    if(m.getName().equals("equals")) return p==a[0];
    if(m.getName().equals("toString")) return "generated-probe-proxy";
    throw new UnsupportedOperationException(m.getName());
  }
  private static void await(CountDownLatch latch,String label) throws Exception {
    if(!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError(label+" timeout");
  }
  private static void barrier() throws Exception {
    CountDownLatch done=new CountDownLatch(1);
    if(!ui.post(done::countDown)) throw new AssertionError("barrier post rejected");
    await(done,"UI barrier");
  }
  private static void publish(Object s) throws Exception {publish.invoke(hub,s);}
  private static Object stateSafe(String title,String status) throws Exception {
    Class<?> playback=loader.loadClass("com.immortal.launcher.PlaybackState");
    Object value=Enum.valueOf((Class)playback,status);
    Constructor<?> c=stateClass.getConstructor(playback,String.class,String.class,String.class,String.class,long.class,long.class,String.class,android.graphics.Bitmap.class,boolean.class,long.class,long.class,String.class,android.media.session.MediaSession.Token.class);
    return c.newInstance(value,title,"","","",12000L,0L,"com.immortal.generated",null,false,0L,0L,"",null);
  }
  private static boolean same(List<Object> rendered,Object expected,int count) {
    if(rendered.size()!=count) return false;
    for(Object s:rendered) if(!Objects.equals(s,expected)) return false;
    return true;
  }
  private static boolean runCase(boolean baseline,String name,Object next,boolean delayed,boolean worker,boolean late,boolean deferred) throws Exception {
    final List<Object> rendered=new ArrayList<>();
    Object l=listener(baseline,rendered);
    CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
    publish(old);
    try {
      if(delayed) {
        if(!ui.post(() -> {entered.countDown();try {if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("block release timeout");}catch(InterruptedException e){throw new RuntimeException(e);}})) throw new AssertionError("block post rejected");
        await(entered,"UI block entered");
      }
      add.invoke(hub,l);
      if(deferred && !rendered.isEmpty()) throw new AssertionError("synchronous render");
      if(worker) {
        barrier();rendered.clear();
        final Throwable[] failure=new Throwable[1];
        Thread w=new Thread(() -> {try{publish(next);}catch(Throwable t){failure[0]=t;}},"generated-publisher");
        w.start();w.join(5000);if(w.isAlive()) throw new AssertionError("publisher did not retire");
        if(failure[0]!=null) throw new AssertionError("publisher failed",failure[0]);
      } else if(next!=old || late) publish(next);
      if(late) listenerClass.getMethod("onNowPlaying",stateClass).invoke(l,old);
      release.countDown();barrier();
      int expected=worker?1:(next==old&&!late?1:2)+(late?1:0);
      boolean pass=same(rendered,next,expected);
      outcomes.put(new JSONObject().put("mode",baseline?"equivalentCapturedBaseline":"actualCandidateFactory").put("case",name).put("pass",pass).put("renderCount",rendered.size()).put("expectedCount",expected).put("callbackClassLoaderIsApk",l.getClass().getClassLoader()==loader));
      return pass;
    } finally {release.countDown();remove.invoke(hub,l);barrier();}
  }
  public static void main(String[] args) {
    int exit=1;
    try {
      if(args.length!=2) throw new IllegalArgumentException("usage: APK privateDexOptDirectory");
      loader=new DexClassLoader(args[0],args[1],null,ClassLoader.getSystemClassLoader());
      Class<?> hc=loader.loadClass("com.immortal.launcher.NowPlayingHub");hub=hc.getField("INSTANCE").get(null);
      stateClass=loader.loadClass("com.immortal.launcher.NowPlayingState");listenerClass=loader.loadClass("com.immortal.launcher.NowPlayingHub$Listener");functionClass=loader.loadClass("kotlin.jvm.functions.Function1");
      unit=loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
      Class<?> fc=loader.loadClass("com.immortal.launcher.HomeNowPlayingListenerKt");
      if(hc.getClassLoader()!=loader||fc.getClassLoader()!=loader||stateClass.getClassLoader()!=loader||functionClass.getClassLoader()!=loader) throw new AssertionError("APK classloader identity mismatch");
      publish=hc.getMethod("publish",stateClass);add=hc.getMethod("addListener",listenerClass);remove=hc.getMethod("removeListener",listenerClass);factory=fc.getMethod("homeNowPlayingListener",functionClass,functionClass);
      old=stateSafe("Generated Original","PLAYING");replacement=stateSafe("Generated Replacement","PLAYING");paused=stateSafe("Generated Paused","PAUSED");
      uiThread=new HandlerThread("generated-home-ui");uiThread.start();ui=new Handler(uiThread.getLooper());
      int candidatePass=0,baselineFailures=0,baselineControls=0;
      for(boolean baseline:new boolean[]{true,false}) {
        boolean[] results={runCase(baseline,"queued-clear",null,true,false,false,false),runCase(baseline,"queued-replacement",replacement,true,false,false,false),runCase(baseline,"queued-paused",paused,true,false,false,false),runCase(baseline,"late-obsolete-callback",replacement,true,false,true,false),runCase(baseline,"ordinary-deferred-replay",old,true,false,false,true),runCase(baseline,"worker-publication-control",replacement,false,true,false,false)};
        for(int i=0;i<results.length;i++) if(!baseline&&results[i])candidatePass++;else if(baseline&&i<4&&!results[i])baselineFailures++;else if(baseline&&i>=4&&results[i])baselineControls++;
      }
      publish(null);barrier();ui.removeCallbacksAndMessages(null);uiThread.quitSafely();uiThread.join(5000);
      if(uiThread.isAlive())throw new AssertionError("UI thread did not retire");
      exit=(candidatePass==6&&baselineFailures==4&&baselineControls==2)?0:1;
      System.out.println(new JSONObject().put("ok",exit==0).put("apkClassloaderIdentity",true).put("candidatePass",candidatePass).put("candidateCases",6).put("equivalentBaselineFailures",baselineFailures).put("baselineControls",baselineControls).put("uiThreadRetired",true).put("cases",outcomes));
    }catch(Throwable t){System.out.println("PROBE_ERROR "+t.getClass().getName()+": "+t.getMessage());t.printStackTrace(System.err);}
    finally {if(ui!=null)ui.removeCallbacksAndMessages(null);if(uiThread!=null){uiThread.quitSafely();try{uiThread.join(5000);}catch(InterruptedException ignored){}}}
    System.exit(exit);
  }
}
