/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package de.badaix.snapcast;

import android.content.Intent;
import android.media.AudioManager;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs the real service class with host boundary stubs; NOT Android/audio proof. */
public final class ReceiverFocusTest {
    static final List<SnapclientService> services = new ArrayList<>();
    static final List<android.app.Service> controls = new ArrayList<>();
    static int passed, failed;
    interface Test { void run() throws Exception; }
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static Object field(SnapclientService s, String n) throws Exception {
        Field f = SnapclientService.class.getDeclaredField(n); f.setAccessible(true); return f.get(s);
    }
    static void set(SnapclientService s, String n, Object v) throws Exception {
        Field f = SnapclientService.class.getDeclaredField(n); f.setAccessible(true); f.set(s, v);
    }
    static SnapclientService service() {
        SnapclientService s = new SnapclientService(); services.add(s); s.onCreate(); return s;
    }
    static void userStart(SnapclientService s) throws Exception {
        SnapclientService.class.getMethod("startPlayerFromUser", String.class, int.class).invoke(s, "127.0.0.1", 1704);
        if(s.audio.result==1) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(((Integer)field(s,"childPid")<=0 || !s.isRunning()) && System.nanoTime()<deadline) {
                Handler.drain();Thread.sleep(5);
            }
            check((Integer)field(s,"childPid")>0 && s.isRunning(),"PID handshake must precede renderer readiness");
        }
    }
    static java.lang.Process child(SnapclientService s) throws Exception { return (java.lang.Process) field(s, "process"); }
    static boolean alive(SnapclientService s) throws Exception { return child(s) != null && child(s).isAlive(); }
    static Bundle command(SnapclientService s, int what, int uid, Bundle data) throws Exception {
        final Bundle[] reply = new Bundle[1];
        Message m = Message.obtain(); m.what=what; m.sendingUid=uid; m.setData(data);
        m.replyTo = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message r) { reply[0]=r.getData(); }
        });
        android.content.Context.bindTarget=s;
        android.app.Service endpoint=(android.app.Service)Class.forName("de.badaix.snapcast.FocusControlService").getConstructor().newInstance();
        controls.add(endpoint);endpoint.onCreate();
        new Messenger(endpoint.onBind(new Intent().setAction("de.badaix.snapcast.FOCUS_CONTROL"))).send(m);
        check(reply[0]!=null, "control must reply"); return reply[0];
    }
    static Bundle query(SnapclientService s) throws Exception { return command(s, 1, 1234, new Bundle()); }
    static Bundle freshStart(SnapclientService s) throws Exception {
        Bundle q=query(s), b=new Bundle();
        b.putString("instance", q.getString("instance")); b.putLong("epoch",q.getLong("epoch"));
        b.putString("host","127.0.0.1"); b.putInt("port",1704); return b;
    }
    static void test(String name, Test body) {
        try { body.run(); passed++; System.out.println("PASS "+name); }
        catch (Throwable e) { failed++; System.out.println("FAIL "+name+": "+e); }
        finally {
            for(android.app.Service endpoint: controls) endpoint.onDestroy();
            controls.clear();
            for (SnapclientService s: services) {
                try { s.onDestroy(); } catch(Throwable ignored) {}
                try {
                    java.lang.Process p=child(s);
                    if(p!=null) { p.destroyForcibly(); check(p.waitFor(2,TimeUnit.SECONDS),"test child cleanup"); }
                } catch(Exception e) { throw new RuntimeException(e); }
            }
            services.clear(); Handler.immediate.clear(); Handler.delayed.clear();
        }
    }
    public static void main(String[] args) throws Exception {
        android.content.Context.nativeDir=args[0];
        test("stock stop cancels queued native restart", () -> {
            SnapclientService s=service(); int[] starts={0}; Runnable retry=()->starts[0]++;
            set(s,"restartRunnable",retry);
            ((Handler)field(s,"restartHandler")).postDelayed(retry,3000);
            s.stopPlayer(); Handler.flushDelayed();
            check(starts[0]==0,"stop must cancel restart callback");
        });
        test("stale reader cannot mark stopped playback running", () -> {
            SnapclientService s=service(); s.stopPlayer();
            Method log=SnapclientService.class.getDeclaredMethod("log",String.class);log.setAccessible(true);
            log.invoke(s,"2026 [Info] (Oboe) Init done");
            check(!s.isRunning(),"old reader must not report player start");
        });
        test("legacy automatic START cannot produce local output", () -> {
            SnapclientService s=service();
            s.onStartCommand(new Intent(s,SnapclientService.class).setAction(SnapclientService.ACTION_START)
                    .putExtra(SnapclientService.EXTRA_HOST,"127.0.0.1").putExtra(SnapclientService.EXTRA_PORT,1704),0,1);
            check(!alive(s),"automatic Start must not launch child");
        });
        if(args.length>1 && args[1].equals("baseline")) {
            System.out.println("BASELINE "+passed+" passed; "+failed+" failed. Three stock service regressions only.");
            if(failed!=0) System.exit(1);
            return;
        }
        for (int result: new int[]{AudioManager.AUDIOFOCUS_REQUEST_FAILED,AudioManager.AUDIOFOCUS_REQUEST_DELAYED}) {
            test("focus result "+result+" cannot start child or gain-resume", () -> {
                SnapclientService s=service(); s.audio.result=result; userStart(s);
                check(!alive(s),"only granted focus admits output");
                s.audio.latest().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
                check(!alive(s),"gain must not restart denied/delayed request");
            });
        }
        for (int loss: new int[]{-1,-2,-3}) {
            test("focus loss "+loss+" kills real child; gain stays stopped", () -> {
                SnapclientService s=service(); userStart(s); java.lang.Process p=child(s);
                check(p!=null && p.isAlive(),"user start should launch actual child");
                check(s.audio.request.pauseOnDuck && !s.audio.request.delayed,"duck must callback; delayed gain disabled");
                AudioManager.OnAudioFocusChangeListener listener=s.audio.latest();
                listener.onAudioFocusChange(loss); Handler.drain();
                check(!p.isAlive() && !alive(s),"loss must kill/reap actual child");
                check(s.audio.abandons==1,"loss abandons receiver focus");
                listener.onAudioFocusChange(1); Handler.flushDelayed();
                check(!alive(s),"gain/retry must not start output");
                check("stopped_confirmed".equals(query(s).getString("outputState")),"query must confirm child termination");
            });
        }
        test("user stop survives delayed gain and pending restart", () -> {
            SnapclientService s=service(); userStart(s);
            AudioManager.OnAudioFocusChangeListener l=s.audio.latest();
            int[] starts={0}; Runnable retry=()->starts[0]++;
            set(s,"restartRunnable",retry);((Handler)field(s,"restartHandler")).postDelayed(retry,3000);
            s.stopPlayer();l.onAudioFocusChange(1);Handler.flushDelayed();
            check(starts[0]==0 && !alive(s),"user stop may not resume");
        });
        test("legacy STOP kills child even while receiver remains bound", () -> {
            SnapclientService s=service(); userStart(s); java.lang.Process p=child(s);
            Class.forName("de.badaix.snapcast.BroadcastReceiver").getMethod("onReceive",android.content.Context.class,Intent.class)
                    .invoke(Class.forName("de.badaix.snapcast.BroadcastReceiver").getConstructor().newInstance(),
                            s,new Intent().setAction("de.badaix.snapcast.STOP_SERVICE"));
            Handler.drain();
            check(!p.isAlive() && !alive(s),"legacy Stop must explicitly stop a bound receiver");
        });
        test("old focus loss cannot kill a new explicit user session", () -> {
            SnapclientService s=service(); userStart(s); AudioManager.OnAudioFocusChangeListener old=s.audio.latest();
            s.stopPlayer(); userStart(s); java.lang.Process p=child(s);
            old.onAudioFocusChange(-1);Handler.drain();
            check(p.isAlive(),"stale focus callback must be ignored");
        });
        test("loss before PID admission cannot release pending GO", () -> {
            SnapclientService s=service();
            SnapclientService.class.getMethod("startPlayerFromUser",String.class,int.class).invoke(s,"127.0.0.1",1704);
            java.lang.Process p=child(s);
            s.audio.latest().onAudioFocusChange(-2);Handler.drain();
            check(!p.isAlive() && !alive(s),"pre-gate loss must stop wrapper; stale admission must not release GO");
        });
        test("unconfirmed teardown is reported and prevents second renderer", () -> {
            SnapclientService s=service();
            java.lang.Process stuck=new java.lang.Process() {
                public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}
                public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}
                public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}
                public int waitFor(){return 0;}
                public boolean waitFor(long t,TimeUnit u){return false;}
                public int exitValue(){throw new IllegalThreadStateException();}
                public void destroy(){}
                public java.lang.Process destroyForcibly(){return this;}
            };
            set(s,"process",stuck);s.stopPlayer();
            check("termination_unconfirmed".equals(query(s).getString("outputState")),"cannot claim suppression after timeout");
            SnapclientService.class.getMethod("startPlayerFromUser",String.class,int.class).invoke(s,"127.0.0.1",1704);
            check(child(s)==stuck && s.audio.listeners.isEmpty(),"must not request focus or duplicate renderer");
            set(s,"process",null);
        });
        test("IPC denies foreign UID and stale instance/epoch; suppress is local", () -> {
            SnapclientService s=service(); Bundle b=freshStart(s);
            check("forbidden".equals(command(s,3,999,b).getString("error")) && !alive(s),"foreign UID must not start");
            b.putString("instance","old");
            check("stale".equals(command(s,3,1234,b).getString("error")) && !alive(s),"wrong service instance must not start");
            b=freshStart(s);b.putLong("epoch",-1);
            check("stale".equals(command(s,3,1234,b).getString("error")) && !alive(s),"old epoch must not start");
            b=freshStart(s);command(s,3,1234,b);check(alive(s),"fresh user start admits");
            Bundle stopped=command(s,2,1234,new Bundle());
            check(!alive(s) && !stopped.getBoolean("processAlive"),"suppression kills only owned child");
            check("stale".equals(command(s,3,1234,b).getString("error")) && !alive(s),"in-flight Start invalidated by suppression");
        });
        System.out.println("RESULT "+passed+" passed; "+failed+" failed. Host service logic only; Android audio not tested.");
        if(failed!=0) System.exit(1);
    }
}
