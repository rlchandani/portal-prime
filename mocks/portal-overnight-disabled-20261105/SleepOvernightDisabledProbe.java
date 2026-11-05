/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import dalvik.system.DexClassLoader;
import android.content.*;
import android.app.admin.DevicePolicyManager;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
public final class SleepOvernightDisabledProbe {
 private static int deniedUrlOpens;
 private static final class ScheduleBoundary extends RuntimeException {ScheduleBoundary(){super("Owned halt before scheduleOvernight config continuation");}}
 private static final class OwnedAccessMarker extends RuntimeException {OwnedAccessMarker(String message){super(message);}}
 private static final class GeneratedContext extends ContextWrapper {
  final Map<String,Object> values=new HashMap<>();final SharedPreferences prefs;
  final List<String> boundaryStack=new ArrayList<>();final List<String> services=new ArrayList<>();
  int preferenceReads,resolverAttempts,dpmAttempts,unexpectedServiceAttempts,writeAttempts,scheduleHalts;
  GeneratedContext(boolean overnight,boolean screensaver,boolean timer,int idle){
   super(null);values.put("overnight_enabled",overnight);values.put("overnight_night_clock",false);values.put("enabled",screensaver);values.put("sleep_timer_enabled",timer);values.put("idle_sleep_min",idle);
   prefs=(SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(SleepOvernightDisabledProbe.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,m,args)->{
    String name=m.getName();
    if(name.equals("toString"))return "GeneratedReadOnlyPreferences";
    if(name.equals("hashCode"))return System.identityHashCode(proxy);
    if(name.equals("equals"))return proxy==args[0];
    if(name.equals("getAll"))return Collections.unmodifiableMap(new HashMap<>(values));
    if(name.equals("contains"))return values.containsKey((String)args[0]);
    if(name.equals("getBoolean")||name.equals("getInt")||name.equals("getLong")||name.equals("getFloat")||name.equals("getString")||name.equals("getStringSet")){String key=(String)args[0];return values.containsKey(key)?values.get(key):args[1];}
    writeAttempts++;throw new OwnedAccessMarker("Denied preferences mutation/observer operation: "+name);
   });
  }
  public Context getApplicationContext(){return this;}
  public SharedPreferences getSharedPreferences(String name,int mode){
   for(StackTraceElement f:Thread.currentThread().getStackTrace())if(f.getClassName().equals("com.immortal.launcher.SleepScheduler")&&f.getMethodName().equals("scheduleOvernight")){
    scheduleHalts++;for(StackTraceElement entry:Thread.currentThread().getStackTrace())if(entry.getClassName().startsWith("com.immortal.launcher."))boundaryStack.add(entry.getClassName()+"."+entry.getMethodName());throw new ScheduleBoundary();
   }
   preferenceReads++;if(!"immortal_screensaver".equals(name))throw new OwnedAccessMarker("Denied unexpected preferences name");return prefs;
  }
  public ContentResolver getContentResolver(){resolverAttempts++;throw new OwnedAccessMarker("Denied resolver before all secure reads/writes");}
  public String getSystemServiceName(Class<?> serviceClass){services.add(serviceClass.getName());if(serviceClass==DevicePolicyManager.class)dpmAttempts++;else unexpectedServiceAttempts++;throw new OwnedAccessMarker("Denied service before real lookup");}
  public Object getSystemService(String name){unexpectedServiceAttempts++;throw new OwnedAccessMarker("Denied named service lookup");}
  public String getPackageName(){throw new OwnedAccessMarker("Denied component/package access");}
  public void startActivity(Intent intent){throw new OwnedAccessMarker("Denied Activity launch");}
 }
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("exact owned APK and owned DEX directory required");
  URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler(){protected URLConnection openConnection(URL u)throws IOException{deniedUrlOpens++;throw new IOException("Owned receiver probe denied URL opens");}protected URLConnection openConnection(URL u,java.net.Proxy proxy)throws IOException{return openConnection(u);}});
  DexClassLoader loader=new DexClassLoader(args[0],args[1],null,SleepOvernightDisabledProbe.class.getClassLoader());
  Class<?> receiverType=loader.loadClass("com.immortal.launcher.SleepReceiver"),scheduler=loader.loadClass("com.immortal.launcher.SleepScheduler");
  if(receiverType.getClassLoader()!=loader||scheduler.getClassLoader()!=loader)throw new IllegalStateException("Unexpected production class origin");
  String action=(String)scheduler.getField("ACTION_OVERNIGHT_START").get(null);
  if(!"com.immortal.launcher.OVERNIGHT_START".equals(action))throw new IllegalStateException("Unexpected action contract");
  Method receive=receiverType.getMethod("onReceive",Context.class,Intent.class);
  String[] names={"disabled-default-settings","disabled-screensaver-off-idle-on","disabled-sleep-timer-on","enabled-dark-control"};
  boolean[] overnight={false,false,false,true},screensaver={true,false,true,true},timer={false,false,true,false};int[] idle={0,60,10,0};
  JSONArray cases=new JSONArray();int passed=0;
  for(int i=0;i<4;i++){
   GeneratedContext context=new GeneratedContext(overnight[i],screensaver[i],timer[i],idle[i]);boolean halted=false;String failure=null;int before=deniedUrlOpens;
   try{Object receiver=receiverType.getConstructor().newInstance();receive.invoke(receiver,context,new Intent(action));failure="Missing owned schedule boundary";}
   catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof ScheduleBoundary)halted=true;else failure=cause.getClass().getName();}
   int expected=i==3?1:0;boolean literal=context.resolverAttempts==expected&&context.dpmAttempts==expected;
   boolean contained=halted&&context.scheduleHalts==1&&context.unexpectedServiceAttempts==0&&context.writeAttempts==0&&deniedUrlOpens==before;
   boolean ok=failure==null&&literal&&contained;if(ok)passed++;
   cases.put(new JSONObject().put("name",names[i]).put("generatedPreferences",new JSONObject(context.values)).put("expectedResolverAttempts",expected).put("expectedDpmAttempts",expected).put("resolverAttempts",context.resolverAttempts).put("dpmAttempts",context.dpmAttempts).put("servicesDeniedBeforeLookup",new JSONArray(context.services)).put("preferenceReads",context.preferenceReads).put("preferencesMutationAttempts",context.writeAttempts).put("unexpectedServiceAttempts",context.unexpectedServiceAttempts).put("scheduleHalts",context.scheduleHalts).put("scheduleBoundaryStack",new JSONArray(context.boundaryStack)).put("failure",failure==null?JSONObject.NULL:failure).put("literalPassed",literal).put("boundaryContained",contained).put("passed",ok));
  }
  System.out.println(new JSONObject().put("caseCount",4).put("passed",passed).put("sourceClassLoaderVerified",true).put("api",android.os.Build.VERSION.SDK_INT).put("action",action).put("entrypoint","Actual APK SleepReceiver.onReceive(Context,Intent)").put("deniedUrlOpens",deniedUrlOpens).put("actualNetworkRequests",0).put("actualSecureReadsWrites",0).put("actualServiceLookups",0).put("actualAlarmsPendingIntents",0).put("scope","Rest-entry boundary attempts only; halted at actual scheduleOvernight config read before alarm/PI operations; no end-to-end lock/wake/nightclock acceptance").put("cases",cases).toString());
 }
}
