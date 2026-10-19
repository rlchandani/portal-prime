/*
 *     This file is part of snapcast
 *     Copyright (C) 2014-2018  Johannes Pohl
 *     Modified 2026-10-19 by Starbright Lab: explicit local playback and audio focus.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.badaix.snapcast;

import static android.os.PowerManager.FULL_WAKE_LOCK;
import static android.os.PowerManager.PARTIAL_WAKE_LOCK;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import de.badaix.snapcast.utils.Settings;

/**
 * Created by johannes on 01.01.16.
 */

public class SnapclientService extends Service {

    private static final String TAG = "Service";

    public static final String EXTRA_HOST = "EXTRA_HOST";
    public static final String EXTRA_PORT = "EXTRA_PORT";
    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_FOCUS_CONTROL = "com.immortal.musicreceiver.FOCUS_CONTROL";
    public static final int MSG_QUERY = 1;
    public static final int MSG_SUPPRESS = 2;
    public static final int MSG_USER_START = 3;
    public static final int MSG_USER_STOP = 4;
    public static final String NOTIFICATION_CHANNEL_ID = "com.immortal.musicreceiver.snapclientservice.defaultchannel";

    private static final String PREF_UNIQUE_ID = "PREF_UNIQUE_ID";
    private static String uniqueID = null;
    private static SnapclientService activeService;

    private final IBinder mBinder = new LocalBinder();
    private java.lang.Process process = null;
    private int childPid = -1;
    private PowerManager.WakeLock wakeLock = null;
    private WifiManager.WifiLock wifiWakeLock = null;
    private Thread reader = null;
    private Thread logReader = null;
    private volatile long connectionGeneration = 0;
    private ConnectionPcmSink pcmSink;
    private boolean running = false;
    private SnapclientListener listener = null;
    private boolean logReceived;
    private final Handler restartHandler = new Handler(Looper.getMainLooper());
    private Runnable restartRunnable = null;
    private String host = null;
    private int port = 0;
    // Lifecycle state and native log delivery run on the service's main thread.
    private boolean playbackRequested = false;
    private boolean focusHeld = false;
    private volatile long epoch = 0;
    private final String instance = UUID.randomUUID().toString();
    private String outputState = "stopped_confirmed";
    private String stopReason = "idle";
    private AudioManager focusManager;
    private AudioFocusRequest focusRequest;
    private AudioManager.OnAudioFocusChangeListener focusListener;
    void handleControlMessage(Message msg) {
        String error = null;
        String[] packages = getPackageManager().getPackagesForUid(msg.sendingUid);
        boolean allowed = false;
        if (packages != null) {
            for (String name : packages) {
                if ("com.immortal.launcher".equals(name) ||
                        "com.immortal.launcher.debug".equals(name))
                    allowed = true;
            }
        }
        // Message.sendingUid is supplied by Android Messenger, not the payload.
        // Package identity alone is insufficient: both apps must share a signer.
        allowed = allowed && msg.sendingUid >= 0 &&
                getPackageManager().checkSignatures(msg.sendingUid, android.os.Process.myUid()) ==
                        PackageManager.SIGNATURE_MATCH;
        if (!allowed) {
            error = "forbidden";
        } else {
            Bundle args = msg.getData();
            switch (msg.what) {
                case MSG_QUERY:
                    break;
                case MSG_SUPPRESS:
                case MSG_USER_STOP:
                    // Authenticated emergency stop intentionally needs no fresh observation.
                    stopReason = msg.what == MSG_SUPPRESS ? "launcher_suppressed" : "user_stopped";
                    suppressLocalOutput();
                    break;
                case MSG_USER_START:
                    if (!matchesSnapshot(args)) {
                        error = "stale";
                    } else {
                        String requestedHost = args.getString("host");
                        int requestedPort = args.getInt("port", 1704);
                        if (requestedHost == null || requestedHost.isEmpty() ||
                                requestedPort < 1 || requestedPort > 65535)
                            error = "invalid_endpoint";
                        else
                            startPlayerFromUser(requestedHost, requestedPort);
                    }
                    break;
                default:
                    error = "unsupported";
            }
        }
        if (msg.replyTo != null) {
            Message reply = Message.obtain();
            reply.what = msg.what;
            Bundle state = allowed ? snapshot() : new Bundle();
            if (error != null)
                state.putString("error", error);
            state.putLong("requestId", msg.getData().getLong("requestId"));
            reply.setData(state);
            try {
                msg.replyTo.send(reply);
            } catch (RemoteException ignored) {
                // A detached launcher has no authority to restart playback.
            }
        }
    }

    private boolean matchesSnapshot(Bundle args) {
        return args.get("instance") instanceof String && instance.equals(args.get("instance")) &&
                args.get("epoch") instanceof Long && ((Long) args.get("epoch")) == epoch;
    }

    private Bundle snapshot() {
        Bundle state = new Bundle();
        state.putInt("protocol", 2);
        state.putString("instance", instance);
        state.putLong("epoch", epoch);
        state.putLong("observedAtElapsedMs", SystemClock.elapsedRealtime());
        state.putString("clientId", getUniqueId(this));
        boolean decoderAlive = process != null && childAlive(process);
        boolean rendererActive = pcmSink != null && pcmSink.hasUnreleasedOutput();
        String reportedOutput = decoderAlive && !rendererActive ?
                "connection_running_unverified" : outputState;
        state.putString("outputState", reportedOutput);
        state.putString("reason", stopReason);
        state.putBoolean("processAlive", decoderAlive);
        state.putBoolean("rendererActive", rendererActive);
        state.putLong("pcmFramesDrained", pcmSink == null ? 0L : pcmSink.drainedFrames());
        state.putBoolean("focusHeld", focusHeld);
        state.putBoolean("playbackRequested", playbackRequested);
        return state;
    }

    public boolean isRunning() {
        return running;
    }

    public void setListener(SnapclientListener listener) {
        this.listener = listener;
    }

    public void initChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                "Snapclient service",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Snapcast player service");
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeService = this;
        initChannels(this);
    }

    static void stopActivePlayer() {
        SnapclientService current = activeService;
        if (current != null)
            current.restartHandler.post(() -> {
                current.stopReason = "user_stopped";
                current.stopService();
            });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null)
            return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            stopReason = "user_stopped";
            stopService();
            return START_NOT_STICKY;
        }
        // Boot, legacy Start broadcasts and sticky recreation cannot authorize audio.
        return START_NOT_STICKY;
    }

    public void startPlayerFromUser(String host, int port) {
        if (host == null || host.isEmpty() || port < 1 || port > 65535)
            return;
        // Keep the component started when the user-facing client unbinds. This
        // action itself cannot produce audio; the service remains NOT_STICKY.
        startService(new Intent(this, SnapclientService.class).setAction("KEEP_ALIVE"));

        Intent stopIntent = new Intent(this, SnapclientService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_media_play)
                        .setTicker(getText(R.string.ticker_text))
                        .setContentTitle(getText(R.string.notification_title))
                        .setContentText(getText(R.string.notification_text))
                        .setContentInfo(host)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(getText(R.string.notification_text)))
                        .addAction(R.drawable.ic_media_stop, getString(R.string.stop), piStop);

        Intent resultIntent = new Intent(this, MainActivity.class);

        // The stack builder object will contain an artificial back stack for the
        // started Activity.
        // This ensures that navigating backward from the Activity leads out of
        // your application to the Home screen.
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        // Adds the back stack for the Intent (but not the Intent itself)
        stackBuilder.addParentStack(MainActivity.class);
        // Adds the Intent that starts the Activity to the top of the stack
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent =
                stackBuilder.getPendingIntent(
                        0,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
        builder.setContentIntent(resultPendingIntent);
        // mId allows you to update the notification later on.
        final Notification notification = builder.build();
        startForeground(123, notification);

        start(host, port);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        stopReason = "service_destroyed";
        stop();
        if (pcmSink != null) pcmSink.close();
        if (activeService == this)
            activeService = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void stopPlayer() {
        Log.d(TAG, "stopPlayer");
        stopReason = "user_stopped";
        stopService();
    }

    private void stopService() {
        Log.d(TAG, "stopService");
        stop();
        stopForeground(true);
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.cancel(123);
    }

    public synchronized static String getUniqueId(Context context) {
        if (uniqueID == null) {
            SharedPreferences sharedPrefs = context.getSharedPreferences(
                    PREF_UNIQUE_ID, Context.MODE_PRIVATE);
            uniqueID = sharedPrefs.getString(PREF_UNIQUE_ID, null);
            if (uniqueID == null) {
                uniqueID = UUID.randomUUID().toString();
                SharedPreferences.Editor editor = sharedPrefs.edit();
                editor.putString(PREF_UNIQUE_ID, uniqueID);
                editor.commit();
            }
        }
        return uniqueID;
    }

    /** One continuously drained PCM connection; speaker ownership is separate. */
    private void startProcess() throws IOException {
        if (process != null && childAlive(process))
            return;
        String gate = "printf '__SNAPCLIENT_PID__%s\\n' \"$$\"; " +
                "IFS= read -r permit || exit 1; [ \"$permit\" = GO ] || exit 1; exec \"$@\"";
        final java.lang.Process launched = new ProcessBuilder().command("sh", "-c", gate, "snapclient",
                getApplicationInfo().nativeLibraryDir + "/libsnapclient.so", "-h", host,
                "-p", Integer.toString(port), "--hostID", getUniqueId(getApplicationContext()),
                "--player", "file:filename=stdout", "--sampleformat", "48000:16:2",
                "--logsink", "stderr", "--logfilter", "*:info").redirectErrorStream(false).start();
        process = launched;
        childPid = -1;
        final long generation = connectionGeneration;
        outputState = "starting_unverified";
        reader = new Thread(() -> {
            try {
                InputStream input = launched.getInputStream();
                // Read only the bounded ASCII PID marker; no buffered text reader may consume PCM.
                byte[] marker = new byte[64];
                int used = 0;
                while (true) {
                    int next = input.read();
                    if (next == -1 || used == marker.length)
                        throw new IOException("Missing decoder PID handshake");
                    if (next == '\n') break;
                    marker[used++] = (byte) next;
                }
                String hello = new String(marker, 0, used, StandardCharsets.US_ASCII);
                if (!hello.matches("__SNAPCLIENT_PID__[0-9]+"))
                    throw new IOException("Invalid decoder PID handshake");
                final int pid = Integer.parseInt(hello.substring("__SNAPCLIENT_PID__".length()));
                if (pid <= 0 || pid == android.os.Process.myPid())
                    throw new IOException("Invalid decoder PID");
                restartHandler.post(() -> {
                    if (process != launched || connectionGeneration != generation || !childAlive(launched))
                        return;
                    childPid = pid;
                    try {
                        launched.getOutputStream().write(new byte[]{'G', 'O', '\n'});
                        launched.getOutputStream().flush();
                        outputState = "process_running_unverified";
                    } catch (IOException e) {
                        stopReason = "renderer_gate_failed";
                        stopService();
                    }
                });
                byte[] pcm = new byte[9_600];
                int count;
                while ((count = input.read(pcm)) != -1) {
                    if (count > 0) pcmSink.consume(generation, pcm, 0, count);
                }
            } catch (IOException | NumberFormatException e) {
                restartHandler.post(() -> {
                    if (process == launched && connectionGeneration == generation) {
                        stopReason = "renderer_gate_failed";
                        stopService();
                    }
                });
            } finally {
                restartHandler.post(() -> {
                    if (process == launched && connectionGeneration == generation) {
                        stopReason = "renderer_exited";
                        stopService();
                    }
                });
            }
        }, "SnapcastPcmDrain");
        logReader = new Thread(() -> {
            try (BufferedReader logs = new BufferedReader(new InputStreamReader(launched.getErrorStream()))) {
                String line;
                while ((line = logs.readLine()) != null) {
                    if (line.length() > 4_096) continue;
                    final String message = line;
                    restartHandler.post(() -> {
                        if (process == launched && connectionGeneration == generation && playbackRequested)
                            log(message);
                    });
                }
            } catch (IOException ignored) {
                // PCM EOF owns connection cleanup; logger failure cannot restart output.
            }
        }, "SnapcastLogs");
        reader.setDaemon(true);
        logReader.setDaemon(true);
        logReceived = false;
        reader.start();
        logReader.start();
    }

    private void ensurePcmSink() {
        if (pcmSink == null) {
            pcmSink = new ConnectionPcmSink(AndroidPcmOutput::new, failedEpoch -> {
                restartHandler.post(() -> {
                    if (epoch == failedEpoch && playbackRequested) {
                        stopReason = "pcm_output_failed";
                        suppressLocalOutput();
                    }
                });
            });
        }
    }

    private void start(String requestedHost, int requestedPort) {
        if (playbackRequested) return;
        try {
            ensurePcmSink();
            if (process != null && childAlive(process) &&
                    (!requestedHost.equals(host) || requestedPort != port)) {
                stopReason = "user_stopped";
                stop();
                if (process != null && childAlive(process)) return;
            }
            // Select the connection before admitting an output, so its session owns this generation.
            final boolean createConnection = process == null || !childAlive(process);
            if (createConnection) {
                pcmSink.selectConnection(++connectionGeneration);
            }
            epoch++;
            playbackRequested = true;
            stopReason = "user_start";
            if (!requestFocus(epoch)) {
                stopReason = "focus_denied";
                suppressLocalOutput();
                return;
            }
            if (!pcmSink.start(epoch)) {
                stopReason = "pcm_output_failed";
                suppressLocalOutput();
                return;
            }
            if (wakeLock == null) {
                PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = manager.newWakeLock(PARTIAL_WAKE_LOCK, "snapcast:SnapcastPcmConnection");
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
            if (wifiWakeLock == null) {
                WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifiWakeLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "snapcast:SnapcastPcmConnection");
            }
            if (!wifiWakeLock.isHeld()) wifiWakeLock.acquire();
            host = requestedHost;
            port = requestedPort;
            if (createConnection) {
                startProcess();
            } else if (process == null || !childAlive(process)) {
                // Do not silently replace a child that died after output admission.
                throw new IOException("Expected PCM connection ended");
            }
            running = pcmSink.isActive();
        } catch (Exception e) {
            stopReason = "start_failed";
            stopService();
        }
    }

    /** Stop hardware output and relinquish focus without disconnecting the shared MA room. */
    private void suppressLocalOutput() {
        epoch++;
        playbackRequested = false;
        if (pcmSink != null) pcmSink.suppress();
        abandonFocus();
        running = false;
        if (listener != null) listener.onPlayerStop(this);
    }

    private boolean requestFocus(final long requestedEpoch) {
        focusManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        focusListener = change -> {
            if (requestedEpoch != epoch || !playbackRequested)
                return;
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                stopReason = "focus_loss_" + change;
                suppressLocalOutput();
            }
            // GAIN never resumes or relaunches a child. A new user Start is required.
        };
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setWillPauseWhenDucked(true)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusListener, restartHandler)
                    .build();
            result = focusManager.requestAudioFocus(focusRequest);
        } else {
            result = focusManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focusHeld;
    }

    private void abandonFocus() {
        if (focusManager != null && focusListener != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null)
                focusManager.abandonAudioFocusRequest(focusRequest);
            else
                focusManager.abandonAudioFocus(focusListener);
        }
        focusRequest = null;
        focusListener = null;
        focusHeld = false;
    }

    private void log(String msg) {
        if (!playbackRequested || !focusHeld || process == null)
            return;
        if (!logReceived) {
            logReceived = true;
            running = true;
            if (listener != null)
                listener.onPlayerStart(this);
        }
        if (listener != null) {
            int idxSeverityOpen = msg.indexOf('[');
            int idxSeverityClose = msg.indexOf(']', idxSeverityOpen);
            int idxTagOpen = msg.indexOf('(', idxSeverityClose);
            int idxTagClose = msg.indexOf(')', idxTagOpen);
            if ((idxSeverityOpen > 0) && (idxSeverityClose > 0)) {
                String severity = msg.substring(idxSeverityOpen + 1, idxSeverityClose);
                String tag = "";
                if ((idxTagOpen > 0) && (idxTagClose > 0))
                    tag = msg.substring(idxTagOpen + 1, idxTagClose);
                String timestamp = msg.substring(0, idxSeverityOpen - 1);
                String message = msg.substring(Math.max(idxSeverityClose, idxTagClose) + 2);
                listener.onLog(SnapclientService.this, timestamp, severity, tag, message);


            }
        }
    }

    private static boolean childAlive(java.lang.Process child) {
        try {
            child.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    private boolean stopProcess() {
        Log.d(TAG, "stopProcess");
        try {
            connectionGeneration++;
            if (pcmSink != null) pcmSink.selectConnection(connectionGeneration);
            if (reader != null) reader.interrupt();
            if (logReader != null) logReader.interrupt();
            if (process != null) {
                if (childPid > 0 && childAlive(process)) {
                    // Public API, same UID; sends SIGKILL on API 29 without root.
                    // PID was received before GO and preserved by shell exec.
                    android.os.Process.killProcess(childPid);
                } else {
                    // Before the handshake there can be no native output.
                    process.destroy();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                        outputState = "termination_unconfirmed";
                        return false;
                    }
                } else if (childAlive(process)) {
                    outputState = "termination_unconfirmed";
                    return false;
                }
            }
            process = null;
            childPid = -1;
            reader = null;
            logReader = null;
            outputState = "stopped_confirmed";
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException)
                Thread.currentThread().interrupt();
            outputState = "termination_unconfirmed";
            return false;
        }
    }

    private void stop() {
        Log.d(TAG, "stop");
        epoch++;
        playbackRequested = false;
        if (pcmSink != null) pcmSink.suppress();
        if (restartRunnable != null)
            restartHandler.removeCallbacks(restartRunnable);
        restartRunnable = null;
        try {
            stopProcess();
            abandonFocus();
            if ((wakeLock != null) && wakeLock.isHeld())
                wakeLock.release();
            if ((wifiWakeLock != null) && wifiWakeLock.isHeld())
                wifiWakeLock.release();
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
            running = process != null && childAlive(process);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (listener != null && !running)
            listener.onPlayerStop(this);
    }

    public interface SnapclientListener {
        void onPlayerStart(SnapclientService snapclientService);

        void onPlayerStop(SnapclientService snapclientService);

        void onLog(SnapclientService snapclientService, String timestamp, String logClass, String tag, String msg);

        void onError(SnapclientService snapclientService, String msg, Exception exception);
    }

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        SnapclientService getService() {
            // Return this instance of LocalService so clients can call public methods
            return SnapclientService.this;
        }
    }

}
