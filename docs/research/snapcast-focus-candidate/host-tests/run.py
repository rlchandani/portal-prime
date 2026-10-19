#!/usr/bin/env python3
"""Compile the actual service, not a model. Android stubs are NOT device proof."""
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent
STUBS = {
    "android/content/Context.java": """
package android.content;
public class Context {
 public static final String AUDIO_SERVICE="audio", POWER_SERVICE="power", WIFI_SERVICE="wifi", UI_MODE_SERVICE="ui", NOTIFICATION_SERVICE="notification";
 public static final int MODE_PRIVATE=0;
 public static final int BIND_AUTO_CREATE=1;
 public static android.app.Service bindTarget;
 public final android.media.AudioManager audio=new android.media.AudioManager();
 public static String nativeDir;
 public Object getSystemService(String s) {
  if(s.equals(AUDIO_SERVICE)) return audio;
  if(s.equals(POWER_SERVICE)) return new android.os.PowerManager();
  if(s.equals(WIFI_SERVICE)) return new android.net.wifi.WifiManager();
  if(s.equals(UI_MODE_SERVICE)) return new android.app.UiModeManager();
  return new android.app.NotificationManager();
 }
 public Context getApplicationContext(){return this;}
 public android.content.pm.ApplicationInfo getApplicationInfo(){return new android.content.pm.ApplicationInfo(nativeDir);}
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public SharedPreferences getSharedPreferences(String n,int m){return new SharedPreferences();}
 public CharSequence getText(int id){return "test";}
 public String getString(int id){return "test";}
 public ComponentName startService(Intent i){return new ComponentName();}
 public boolean stopService(Intent i){return true;}
 public boolean bindService(Intent i,ServiceConnection c,int flags){c.onServiceConnected(new ComponentName(),bindTarget.onBind(i));return true;}
 public void unbindService(ServiceConnection c){}
}
""",
    "android/content/ServiceConnection.java": "package android.content; public interface ServiceConnection {void onServiceConnected(ComponentName n,android.os.IBinder b);void onServiceDisconnected(ComponentName n);}",
    "android/content/ComponentName.java": "package android.content; public class ComponentName {}",
    "android/content/BroadcastReceiver.java": "package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}",
    "android/content/Intent.java": """
package android.content;
public class Intent {
 private String action; private android.os.Bundle extras=new android.os.Bundle();
 public Intent(){} public Intent(Context c,Class<?> k){}
 public String getAction(){return action;} public Intent setAction(String a){action=a;return this;}
 public Intent putExtra(String k,String v){extras.putString(k,v);return this;}
 public Intent putExtra(String k,int v){extras.putInt(k,v);return this;}
 public String getStringExtra(String k){return extras.getString(k);}
 public int getIntExtra(String k,int d){return extras.getInt(k,d);}
}
""",
    "android/content/SharedPreferences.java": """
package android.content;
public class SharedPreferences {
 public String getString(String k,String d){return d;} public Editor edit(){return new Editor();}
 public static class Editor {public Editor putString(String k,String v){return this;} public boolean commit(){return true;}}
}
""",
    "android/content/pm/ApplicationInfo.java": "package android.content.pm; public class ApplicationInfo {public String nativeLibraryDir; public ApplicationInfo(String d){nativeLibraryDir=d;}}",
    "android/content/pm/PackageManager.java": """
package android.content.pm; public class PackageManager {
 public String[] getPackagesForUid(int uid){return new String[]{uid==1234?"com.immortal.launcher":"untrusted.app"};}
}
""",
    "android/content/res/Configuration.java": "package android.content.res; public class Configuration {public static final int UI_MODE_TYPE_TELEVISION=4;}",
    "android/app/Service.java": """
package android.app; public class Service extends android.content.Context {
 public static final int START_STICKY=1,START_NOT_STICKY=2;
 public void onCreate(){} public void onDestroy(){}
 public int onStartCommand(android.content.Intent i,int f,int id){return START_NOT_STICKY;}
 public android.os.IBinder onBind(android.content.Intent i){return null;}
 public void startForeground(int id,Notification n){} public void stopForeground(boolean b){}
}
""",
    "android/app/Notification.java": "package android.app; public class Notification {}",
    "android/app/NotificationChannel.java": "package android.app; public class NotificationChannel {public NotificationChannel(String i,String n,int p){} public void setDescription(String s){}}",
    "android/app/NotificationManager.java": "package android.app; public class NotificationManager {public static final int IMPORTANCE_LOW=2; public void createNotificationChannel(NotificationChannel c){} public void cancel(int id){}}",
    "android/app/PendingIntent.java": "package android.app; public class PendingIntent {public static final int FLAG_IMMUTABLE=1,FLAG_UPDATE_CURRENT=2; public static PendingIntent getService(android.content.Context c,int id,android.content.Intent i,int f){return new PendingIntent();}}",
    "android/app/UiModeManager.java": "package android.app; public class UiModeManager {public int getCurrentModeType(){return 1;}}",
    "android/net/wifi/WifiManager.java": """
package android.net.wifi; public class WifiManager {
 public static final int WIFI_MODE_FULL_HIGH_PERF=3;
 public WifiLock createWifiLock(int m,String n){return new WifiLock();}
 public static class WifiLock {private boolean held; public void acquire(){held=true;} public void release(){held=false;} public boolean isHeld(){return held;}}
}
""",
    "android/os/PowerManager.java": """
package android.os; public class PowerManager {
 public static final int FULL_WAKE_LOCK=26,PARTIAL_WAKE_LOCK=1;
 public WakeLock newWakeLock(int m,String n){return new WakeLock();}
 public static class WakeLock {private boolean held; public void acquire(){held=true;} public void release(){held=false;} public boolean isHeld(){return held;}}
}
""",
    "android/os/IBinder.java": "package android.os; public interface IBinder {}",
    "android/os/Binder.java": "package android.os; public class Binder implements IBinder {}",
    "android/os/Build.java": "package android.os; public class Build {public static class VERSION {public static int SDK_INT=29;} public static class VERSION_CODES {public static final int O=26,JELLY_BEAN=16,JELLY_BEAN_MR1=17;}}",
    "android/os/Looper.java": "package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}",
    "android/os/Handler.java": """
package android.os; import java.util.*;
public class Handler {
 public static final List<Runnable> immediate=new java.util.concurrent.CopyOnWriteArrayList<>(), delayed=new java.util.concurrent.CopyOnWriteArrayList<>();
 public Handler(){} public Handler(Looper l){}
 public boolean post(Runnable r){immediate.add(r);return true;}
 public boolean postDelayed(Runnable r,long ms){delayed.add(r);return true;}
 public void removeCallbacks(Runnable r){immediate.removeIf(x->x==r);delayed.removeIf(x->x==r);}
 public void handleMessage(Message m){}
 public static void drain(){while(!immediate.isEmpty()) immediate.remove(0).run();}
 public static void flushDelayed(){List<Runnable> copy=new ArrayList<>(delayed);delayed.clear();for(Runnable r:copy)r.run();drain();}
}
""",
    "android/os/Bundle.java": """
package android.os; import java.util.*;
public class Bundle {
 private final Map<String,Object> m=new HashMap<>();
 public void putString(String k,String v){m.put(k,v);} public String getString(String k){return (String)m.get(k);}
 public void putLong(String k,long v){m.put(k,v);} public long getLong(String k){return getLong(k,0);} public long getLong(String k,long d){return m.containsKey(k)?((Number)m.get(k)).longValue():d;}
 public void putInt(String k,int v){m.put(k,v);} public int getInt(String k){return getInt(k,0);} public int getInt(String k,int d){return m.containsKey(k)?((Number)m.get(k)).intValue():d;}
 public void putBoolean(String k,boolean v){m.put(k,v);} public boolean getBoolean(String k){return Boolean.TRUE.equals(m.get(k));}
 public boolean containsKey(String k){return m.containsKey(k);}
}
""",
    "android/os/Message.java": """
package android.os; public class Message {
 public int what,sendingUid=-1; public Messenger replyTo; private Bundle b=new Bundle();
 public static Message obtain(){return new Message();} public Bundle getData(){return b;} public void setData(Bundle data){b=data;}
}
""",
    "android/os/Messenger.java": """
package android.os; public class Messenger {
 private final Handler h; public Messenger(Handler handler){h=handler;}
 public Messenger(IBinder b){h=((MessengerBinder)b).h;}
 public IBinder getBinder(){return new MessengerBinder(h);}
 public void send(Message m)throws RemoteException{h.handleMessage(m);}
 private static class MessengerBinder extends Binder {final Handler h; MessengerBinder(Handler handler){h=handler;}}
}
""",
    "android/os/RemoteException.java": "package android.os; public class RemoteException extends Exception {}",
    "android/os/SystemClock.java": "package android.os; public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}",
    "android/os/Process.java": "package android.os; public class Process {public static final int THREAD_PRIORITY_URGENT_AUDIO=-19,THREAD_PRIORITY_DEFAULT=0; public static int kills; public static void setThreadPriority(int p){} public static int myPid(){return (int)java.lang.ProcessHandle.current().pid();} public static void killProcess(int pid){kills++;java.lang.ProcessHandle.of(pid).orElseThrow().destroyForcibly();}}",
    "android/media/AudioAttributes.java": """
package android.media; public class AudioAttributes {
 public static final int USAGE_MEDIA=1,CONTENT_TYPE_MUSIC=2;
 public static class Builder {public Builder setUsage(int n){return this;} public Builder setContentType(int n){return this;} public AudioAttributes build(){return new AudioAttributes();}}
}
""",
    "android/media/AudioFocusRequest.java": """
package android.media; public class AudioFocusRequest {
 public AudioManager.OnAudioFocusChangeListener listener; public boolean pauseOnDuck,delayed;
 public static class Builder {
  final AudioFocusRequest r=new AudioFocusRequest(); public Builder(int gain){}
  public Builder setAudioAttributes(AudioAttributes a){return this;}
  public Builder setWillPauseWhenDucked(boolean b){r.pauseOnDuck=b;return this;}
  public Builder setAcceptsDelayedFocusGain(boolean b){r.delayed=b;return this;}
  public Builder setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener l,android.os.Handler h){r.listener=l;return this;}
  public AudioFocusRequest build(){return r;}
 }
}
""",
    "android/media/AudioManager.java": """
package android.media; import java.util.*;
public class AudioManager {
 public static final int AUDIOFOCUS_GAIN=1,AUDIOFOCUS_LOSS=-1,AUDIOFOCUS_LOSS_TRANSIENT=-2,AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK=-3,AUDIOFOCUS_REQUEST_GRANTED=1,AUDIOFOCUS_REQUEST_FAILED=0,AUDIOFOCUS_REQUEST_DELAYED=2,STREAM_MUSIC=3;
 public static final String PROPERTY_OUTPUT_SAMPLE_RATE="rate",PROPERTY_OUTPUT_FRAMES_PER_BUFFER="frames";
 public interface OnAudioFocusChangeListener {void onAudioFocusChange(int n);}
 public int result=1,abandons; public AudioFocusRequest request;
 public final List<OnAudioFocusChangeListener> listeners=new ArrayList<>();
 public int requestAudioFocus(AudioFocusRequest r){request=r;listeners.add(r.listener);return result;}
 public int requestAudioFocus(OnAudioFocusChangeListener l,int stream,int gain){listeners.add(l);return result;}
 public int abandonAudioFocusRequest(AudioFocusRequest r){abandons++;return 1;}
 public int abandonAudioFocus(OnAudioFocusChangeListener l){abandons++;return 1;}
 public String getProperty(String p){return "48000";}
 public OnAudioFocusChangeListener latest(){return listeners.get(listeners.size()-1);}
}
""",
    "android/util/Log.java": "package android.util; public class Log {public static int d(String t,String m){return 0;} public static int i(String t,String m){return 0;} public static int w(String t,String m){return 0;} public static int e(String t,String m){return 0;}}",
    "androidx/core/app/NotificationCompat.java": """
package androidx.core.app; public class NotificationCompat {
 public static class BigTextStyle {public BigTextStyle bigText(CharSequence s){return this;}}
 public static class Builder {
  public Builder(android.content.Context c,String ch){}
  public Builder setSmallIcon(int n){return this;} public Builder setTicker(CharSequence s){return this;} public Builder setContentTitle(CharSequence s){return this;} public Builder setContentText(CharSequence s){return this;} public Builder setContentInfo(String s){return this;} public Builder setStyle(BigTextStyle s){return this;} public Builder addAction(int id,String s,android.app.PendingIntent p){return this;} public Builder setContentIntent(android.app.PendingIntent p){return this;} public android.app.Notification build(){return new android.app.Notification();}
 }
}
""",
    "androidx/core/app/TaskStackBuilder.java": "package androidx.core.app; public class TaskStackBuilder {public static TaskStackBuilder create(android.content.Context c){return new TaskStackBuilder();} public TaskStackBuilder addParentStack(Class<?> k){return this;} public TaskStackBuilder addNextIntent(android.content.Intent i){return this;} public android.app.PendingIntent getPendingIntent(int id,int f){return null;}}",
    "de/badaix/snapcast/utils/Settings.java": "package de.badaix.snapcast.utils; public class Settings {public static Settings getInstance(android.content.Context c){return new Settings();} public String getAudioEngine(){return \"Oboe\";} public boolean doResample(){return false;}}",
    "de/badaix/snapcast/MainActivity.java": "package de.badaix.snapcast; public class MainActivity {}",
    "de/badaix/snapcast/R.java": "package de.badaix.snapcast; public class R {public static class drawable {public static final int ic_media_play=1,ic_media_stop=2;} public static class string {public static final int ticker_text=1,notification_title=2,notification_text=3,stop=4;}}",
}

source = pathlib.Path(sys.argv[1]).resolve()
build = ROOT / "generated"
if build.exists():
    shutil.rmtree(build)
build.mkdir()
for name, code in STUBS.items():
    path = build / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(code)
native = build / "native"
native.mkdir()
child = native / "libsnapclient.so"
child.write_text("#!/bin/sh\ntrap '' TERM\nprintf '2026 [Info] (test) READY\\n'\nexec sleep 600\n")
child.chmod(0o700)
classes = build / "classes"
classes.mkdir()
files = [str(p) for p in build.rglob("*.java")]
subprocess.run(["javac", "--release", "17", "-d", str(classes), *files,
                str(source), *([str(source.parent / "BroadcastReceiver.java")] if
                (source.parent / "FocusControlService.java").exists() else []),
                *([str(source.parent / "FocusControlService.java")] if
                (source.parent / "FocusControlService.java").exists() else []),
                str(ROOT / "ReceiverFocusTest.java")], check=True)
result = subprocess.run(["java", "-cp", str(classes), "de.badaix.snapcast.ReceiverFocusTest", str(native),
                         *(["baseline"] if len(sys.argv) > 2 and sys.argv[2] == "baseline" else [])])
sys.exit(result.returncode)
