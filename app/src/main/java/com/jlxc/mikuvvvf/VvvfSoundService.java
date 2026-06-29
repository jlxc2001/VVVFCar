package com.jlxc.mikuvvvf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class VvvfSoundService extends Service {
    public static final String ACTION_START = "com.jlxc.mikuvvvf.action.START";
    public static final String ACTION_STOP = "com.jlxc.mikuvvvf.action.STOP";
    public static final String ACTION_SET_SPEED = "com.jlxc.mikuvvvf.action.SET_SPEED";
    public static final String ACTION_SET_STYLE = "com.jlxc.mikuvvvf.action.SET_STYLE";
    public static final String ACTION_SET_VOLUME = "com.jlxc.mikuvvvf.action.SET_VOLUME";
    public static final String ACTION_SET_MUTE = "com.jlxc.mikuvvvf.action.SET_MUTE";
    public static final String ACTION_SET_HOOK = "com.jlxc.mikuvvvf.action.SET_HOOK";
    public static final String ACTION_SET_FOREGROUND = "com.jlxc.mikuvvvf.action.SET_FOREGROUND";
    public static final String ACTION_SET_BACKGROUND_MUTE = "com.jlxc.mikuvvvf.action.SET_BACKGROUND_MUTE";
    public static final String ACTION_SET_RPM_BINDING = "com.jlxc.mikuvvvf.action.SET_RPM_BINDING";
    public static final String ACTION_SET_CUSTOM_VVVF = "com.jlxc.mikuvvvf.action.SET_CUSTOM_VVVF";
    public static final String ACTION_STATUS = "com.jlxc.mikuvvvf.action.STATUS";

    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_STYLE = "style";
    public static final String EXTRA_VOLUME = "volume";
    public static final String EXTRA_MUTE = "mute";
    public static final String EXTRA_HOOK_ENABLED = "hook_enabled";
    public static final String EXTRA_FOREGROUND = "foreground";
    public static final String EXTRA_BACKGROUND_MUTE = "background_mute";
    public static final String EXTRA_RPM_BINDING = "rpm_binding";
    public static final String EXTRA_CUSTOM_CUT1 = "custom_cut1";
    public static final String EXTRA_CUSTOM_CUT2 = "custom_cut2";
    public static final String EXTRA_CUSTOM_MAX_SPEED = "custom_max_speed";
    public static final String EXTRA_CUSTOM_ASYNC_HZ = "custom_async_hz";
    public static final String EXTRA_CUSTOM_SYNC_PULSES = "custom_sync_pulses";
    public static final String EXTRA_CUSTOM_WIDE_PULSES = "custom_wide_pulses";
    public static final String EXTRA_STATUS_SPEED = "status_speed";
    public static final String EXTRA_STATUS_TARGET_SPEED = "status_target_speed";
    public static final String EXTRA_STATUS_RPM = "status_rpm";
    public static final String EXTRA_STATUS_THROTTLE = "status_throttle";
    public static final String EXTRA_STATUS_ACCEL = "status_accel";
    public static final String EXTRA_STATUS_STYLE = "status_style";
    public static final String EXTRA_STATUS_STAGE = "status_stage";
    public static final String EXTRA_STATUS_SOURCE = "status_source";
    public static final String EXTRA_STATUS_HOOK = "status_hook";
    public static final String EXTRA_STATUS_BACKGROUND_MUTE = "status_background_mute";
    public static final String EXTRA_STATUS_RPM_BINDING = "status_rpm_binding";
    public static final String EXTRA_STATUS_EFFECTIVE_MUTE = "status_effective_mute";
    public static final String EXTRA_STATUS_CUSTOM_SUMMARY = "status_custom_summary";

    public static final int UDP_PORT = 47230;

    private static final String CHANNEL_ID = "miku_vvvf_sound";
    private static final int NOTIFICATION_ID = 3939;

    private static final String PREFS = "miku_vvvf_settings";
    private static final String KEY_STYLE = "style";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_MUTED = "muted";
    private static final String KEY_HOOK = "hook";
    private static final String KEY_BACKGROUND_MUTE = "background_mute";
    private static final String KEY_RPM_BINDING = "rpm_binding";
    private static final String KEY_CUSTOM_CUT1 = "custom_cut1";
    private static final String KEY_CUSTOM_CUT2 = "custom_cut2";
    private static final String KEY_CUSTOM_MAX = "custom_max";
    private static final String KEY_CUSTOM_ASYNC = "custom_async";
    private static final String KEY_CUSTOM_SYNC = "custom_sync";
    private static final String KEY_CUSTOM_WIDE = "custom_wide";

    private SharedPreferences prefs;
    private VvvfSynthEngine engine;
    private final AtomicBoolean udpRunning = new AtomicBoolean(false);
    private Thread udpThread;
    private DatagramSocket udpSocket;
    private VehicleDataProvider vehicleDataProvider;
    private volatile String hookStatus = "Hook idle";
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRunnable = new Runnable() {
        @Override public void run() {
            broadcastStatus();
            statusHandler.postDelayed(this, 250);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        engine = new VvvfSynthEngine(getApplicationContext());
        engine.setStatusListener(text -> {});
        loadPreferencesIntoEngine();
        vehicleDataProvider = new VehicleDataProvider(getApplicationContext(), new VehicleDataProvider.Listener() {
            @Override public void onVehicleData(VehicleDataProvider.VehicleSnapshot snapshot) {
                if (snapshot == null || !snapshot.valid) return;
                engine.setVehicleStateFromHook(snapshot.speedKmh, snapshot.rpm, snapshot.throttle, snapshot.source);
                hookStatus = snapshot.rawSummary;
            }
            @Override public void onVehicleProviderStatus(String text) {
                hookStatus = text == null ? "" : text;
            }
        });
        vehicleDataProvider.setPollIntervalMs(500);
        vehicleDataProvider.setEnabled(prefs == null || prefs.getBoolean(KEY_HOOK, true));
        vehicleDataProvider.start();
        startForeground(NOTIFICATION_ID, buildNotification("Ready · Hook ON 500ms · UDP " + UDP_PORT + " · " + engine.getSampleVvvfStatus()));
        startUdpServer();
        engine.start();
        statusHandler.removeCallbacks(statusRunnable);
        statusHandler.post(statusRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelfSafely();
                return START_NOT_STICKY;
            } else if (ACTION_SET_SPEED.equals(action)) {
                engine.setSpeedKmh(intent.getFloatExtra(EXTRA_SPEED, engine.getTargetSpeedKmh()));
                updateNotification();
            } else if (ACTION_SET_STYLE.equals(action)) {
                String styleName = intent.getStringExtra(EXTRA_STYLE);
                applyStyle(styleName);
                saveString(KEY_STYLE, engine.getStyle().name());
                updateNotification();
            } else if (ACTION_SET_VOLUME.equals(action)) {
                engine.setVolume(intent.getFloatExtra(EXTRA_VOLUME, engine.getVolume()));
                saveFloat(KEY_VOLUME, engine.getVolume());
                updateNotification();
            } else if (ACTION_SET_MUTE.equals(action)) {
                engine.setMuted(intent.getBooleanExtra(EXTRA_MUTE, engine.isMuted()));
                saveBoolean(KEY_MUTED, engine.isMuted());
                updateNotification();
            } else if (ACTION_SET_HOOK.equals(action)) {
                boolean enabled = intent.getBooleanExtra(EXTRA_HOOK_ENABLED, vehicleDataProvider == null || vehicleDataProvider.isEnabled());
                if (vehicleDataProvider != null) vehicleDataProvider.setEnabled(enabled);
                saveBoolean(KEY_HOOK, enabled);
                updateNotification();
            } else if (ACTION_SET_FOREGROUND.equals(action)) {
                engine.setAppInForeground(intent.getBooleanExtra(EXTRA_FOREGROUND, true));
                updateNotification();
            } else if (ACTION_SET_BACKGROUND_MUTE.equals(action)) {
                engine.setMuteWhenBackground(intent.getBooleanExtra(EXTRA_BACKGROUND_MUTE, engine.isMuteWhenBackground()));
                saveBoolean(KEY_BACKGROUND_MUTE, engine.isMuteWhenBackground());
                updateNotification();
            } else if (ACTION_SET_RPM_BINDING.equals(action)) {
                engine.setRpmBindingEnabled(intent.getBooleanExtra(EXTRA_RPM_BINDING, engine.isRpmBindingEnabled()));
                saveBoolean(KEY_RPM_BINDING, engine.isRpmBindingEnabled());
                updateNotification();
            } else if (ACTION_SET_CUSTOM_VVVF.equals(action)) {
                applyCustomVvvfFromIntent(intent);
                updateNotification();
            } else {
                updateNotification();
            }
        }
        if (!engine.isRunning()) engine.start();
        if (!udpRunning.get()) startUdpServer();
        if (vehicleDataProvider != null) vehicleDataProvider.start();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        statusHandler.removeCallbacks(statusRunnable);
        stopUdpServer();
        if (vehicleDataProvider != null) {
            vehicleDataProvider.stop();
            vehicleDataProvider = null;
        }
        engine.stop();
        super.onDestroy();
    }

    private void stopSelfSafely() {
        statusHandler.removeCallbacks(statusRunnable);
        stopUdpServer();
        if (vehicleDataProvider != null) {
            vehicleDataProvider.stop();
            vehicleDataProvider = null;
        }
        engine.stop();
        stopForeground(true);
        stopSelf();
    }

    private void startUdpServer() {
        if (!udpRunning.compareAndSet(false, true)) return;
        udpThread = new Thread(() -> {
            byte[] buf = new byte[512];
            try {
                udpSocket = new DatagramSocket(UDP_PORT);
                udpSocket.setReuseAddress(true);
                while (udpRunning.get()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);
                    String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                    handleUdpMessage(msg, packet.getAddress(), packet.getPort());
                }
            } catch (Throwable ignored) {
            } finally {
                closeUdpSocket();
                udpRunning.set(false);
            }
        }, "MikuVVVF-UDP");
        udpThread.start();
    }

    private void stopUdpServer() {
        udpRunning.set(false);
        closeUdpSocket();
        if (udpThread != null) {
            udpThread.interrupt();
            try { udpThread.join(300); } catch (InterruptedException ignored) {}
            udpThread = null;
        }
    }

    private void closeUdpSocket() {
        if (udpSocket != null) {
            try { udpSocket.close(); } catch (Throwable ignored) {}
            udpSocket = null;
        }
    }

    private void handleUdpMessage(String msg, InetAddress remote, int remotePort) {
        if (msg == null || msg.length() == 0) return;
        String[] parts = msg.split("\\s+");
        String cmd = parts[0].toUpperCase(Locale.US);
        try {
            switch (cmd) {
                case "SPEED":
                    if (parts.length >= 2) engine.setSpeedKmh(Float.parseFloat(parts[1]));
                    break;
                case "STATE":
                    // STATE speed rpm throttle. rpm/throttle are optional.
                    if (parts.length >= 4) {
                        engine.setVehicleState(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Float.parseFloat(parts[3]));
                    } else if (parts.length >= 3) {
                        engine.setVehicleState(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), -1f);
                    } else if (parts.length >= 2) {
                        engine.setSpeedKmh(Float.parseFloat(parts[1]));
                    }
                    break;
                case "STYLE":
                    if (parts.length >= 2) {
                        applyStyle(parts[1]);
                        saveString(KEY_STYLE, engine.getStyle().name());
                    }
                    break;
                case "VOLUME":
                    if (parts.length >= 2) {
                        engine.setVolume(Float.parseFloat(parts[1]));
                        saveFloat(KEY_VOLUME, engine.getVolume());
                    }
                    break;
                case "MUTE":
                    engine.setMuted(parts.length < 2 || !"0".equals(parts[1]));
                    saveBoolean(KEY_MUTED, engine.isMuted());
                    break;
                case "UNMUTE":
                    engine.setMuted(false);
                    saveBoolean(KEY_MUTED, false);
                    break;
                case "HOOK":
                    if (vehicleDataProvider != null) {
                        boolean on = parts.length < 2 || !("0".equals(parts[1]) || "OFF".equalsIgnoreCase(parts[1]) || "FALSE".equalsIgnoreCase(parts[1]));
                        vehicleDataProvider.setEnabled(on);
                        saveBoolean(KEY_HOOK, on);
                    }
                    break;
                case "BGMUTE":
                case "BACKGROUND_MUTE":
                    engine.setMuteWhenBackground(parts.length < 2 || !("0".equals(parts[1]) || "OFF".equalsIgnoreCase(parts[1]) || "FALSE".equalsIgnoreCase(parts[1])));
                    saveBoolean(KEY_BACKGROUND_MUTE, engine.isMuteWhenBackground());
                    break;
                case "RPMBIND":
                case "RPM_BIND":
                    engine.setRpmBindingEnabled(parts.length < 2 || !("0".equals(parts[1]) || "OFF".equalsIgnoreCase(parts[1]) || "FALSE".equalsIgnoreCase(parts[1])));
                    saveBoolean(KEY_RPM_BINDING, engine.isRpmBindingEnabled());
                    break;
                case "CUSTOM":
                    if (parts.length >= 7) {
                        applyCustomVvvf(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                                Double.parseDouble(parts[4]), Integer.parseInt(parts[5]), Integer.parseInt(parts[6]));
                    }
                    break;
                case "POLL":
                    if (parts.length >= 2 && vehicleDataProvider != null) vehicleDataProvider.setPollIntervalMs(Integer.parseInt(parts[1]));
                    break;
                case "PING":
                    replyUdp("PONG MIKU_VVVF " + engine.getTargetSpeedKmh() + " " + engine.getStyle().name() + " rpmBind=" + engine.isRpmBindingEnabled() + " bgMute=" + engine.isMuteWhenBackground() + " " + hookStatus, remote, remotePort);
                    break;
                case "STOP":
                    engine.setSpeedKmh(0f);
                    engine.setMuted(true);
                    saveBoolean(KEY_MUTED, true);
                    break;
            }
        } catch (Throwable ignored) {
        }
    }

    private void replyUdp(String text, InetAddress remote, int remotePort) {
        try {
            if (udpSocket == null || remote == null) return;
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            udpSocket.send(new DatagramPacket(data, data.length, remote, remotePort));
        } catch (Throwable ignored) {}
    }

    private void applyStyle(String styleName) {
        if (styleName == null) return;
        String s = styleName.trim().toUpperCase(Locale.US);
        if (s.contains("CUSTOM") || s.contains("USER") || s.contains("自定义") || s.contains("9P") || s.contains("WIDE3")) {
            engine.setStyle(VvvfSynthEngine.Style.CUSTOM_VVVF);
        } else if (s.contains("SAMPLE") || s.contains("REAL") || s.contains("VVVF_SAMPLE") || s.contains("真实") || s.contains("采样") || s.contains("录音")) {
            engine.setStyle(VvvfSynthEngine.Style.SAMPLE_VVVF_0_140);
        } else if (s.contains("AIR") || s.contains("PLANE") || s.contains("JET") || s.contains("TURBINE") || s.contains("飞机") || s.contains("涡扇")) {
            engine.setStyle(VvvfSynthEngine.Style.AIRCRAFT_TURBINE);
        } else if (s.contains("POP") || s.contains("BANG") || s.contains("ANTI") || s.contains("TURBO") || s.contains("偏时") || s.contains("回火") || s.contains("放炮")) {
            engine.setStyle(VvvfSynthEngine.Style.POP_BANG_TURBO);
        } else if (s.contains("NATURAL") || s.contains("ASPIRATED") || s.equals("NA") || s.contains("自然吸气") || s.contains("自吸")) {
            engine.setStyle(VvvfSynthEngine.Style.NATURAL_ASPIRATED);
        } else if (s.contains("ROTARY") || s.contains("WANKEL") || s.contains("转子")) {
            engine.setStyle(VvvfSynthEngine.Style.ROTARY);
        } else if (s.contains("SUPERCHARGED") || s.contains("HELLCAT") || s.contains("V8") || s.contains("地狱猫") || s.contains("机械增压")) {
            engine.setStyle(VvvfSynthEngine.Style.SUPERCHARGED_V8);
        } else if (s.contains("SIEMENS") || s.contains("GUANGZHOU") || s.contains("GZ") || s.contains("A1") || s.contains("广东") || s.contains("广州") || s.contains("西门子")) {
            engine.setStyle(VvvfSynthEngine.Style.SIEMENS_GZ_GTO);
        } else if (s.contains("IGBT")) {
            engine.setStyle(VvvfSynthEngine.Style.IGBT);
        } else if (s.contains("GTO")) {
            engine.setStyle(VvvfSynthEngine.Style.GTO);
        }
    }

    private void loadPreferencesIntoEngine() {
        if (prefs == null || engine == null) return;
        applyStyle(prefs.getString(KEY_STYLE, VvvfSynthEngine.Style.SAMPLE_VVVF_0_140.name()));
        engine.setVolume(prefs.getFloat(KEY_VOLUME, 0.55f));
        engine.setMuted(prefs.getBoolean(KEY_MUTED, false));
        engine.setMuteWhenBackground(prefs.getBoolean(KEY_BACKGROUND_MUTE, true));
        engine.setRpmBindingEnabled(prefs.getBoolean(KEY_RPM_BINDING, false));
        engine.setCustomVvvfParams(
                prefs.getFloat(KEY_CUSTOM_CUT1, 34.5f),
                prefs.getFloat(KEY_CUSTOM_CUT2, 38.0f),
                prefs.getFloat(KEY_CUSTOM_MAX, 160.0f),
                prefs.getFloat(KEY_CUSTOM_ASYNC, 1050.0f),
                prefs.getInt(KEY_CUSTOM_SYNC, 9),
                prefs.getInt(KEY_CUSTOM_WIDE, 3));
    }

    private void applyCustomVvvfFromIntent(Intent intent) {
        if (intent == null || engine == null) return;
        double cut1 = intent.getDoubleExtra(EXTRA_CUSTOM_CUT1, prefs == null ? 34.5 : prefs.getFloat(KEY_CUSTOM_CUT1, 34.5f));
        double cut2 = intent.getDoubleExtra(EXTRA_CUSTOM_CUT2, prefs == null ? 38.0 : prefs.getFloat(KEY_CUSTOM_CUT2, 38.0f));
        double max = intent.getDoubleExtra(EXTRA_CUSTOM_MAX_SPEED, prefs == null ? 160.0 : prefs.getFloat(KEY_CUSTOM_MAX, 160.0f));
        double async = intent.getDoubleExtra(EXTRA_CUSTOM_ASYNC_HZ, prefs == null ? 1050.0 : prefs.getFloat(KEY_CUSTOM_ASYNC, 1050.0f));
        int sync = intent.getIntExtra(EXTRA_CUSTOM_SYNC_PULSES, prefs == null ? 9 : prefs.getInt(KEY_CUSTOM_SYNC, 9));
        int wide = intent.getIntExtra(EXTRA_CUSTOM_WIDE_PULSES, prefs == null ? 3 : prefs.getInt(KEY_CUSTOM_WIDE, 3));
        applyCustomVvvf(cut1, cut2, max, async, sync, wide);
    }

    private void applyCustomVvvf(double cut1, double cut2, double max, double async, int sync, int wide) {
        engine.setCustomVvvfParams(cut1, cut2, max, async, sync, wide);
        if (prefs != null) prefs.edit()
                .putFloat(KEY_CUSTOM_CUT1, (float) engine.getCustomCut1Kmh())
                .putFloat(KEY_CUSTOM_CUT2, (float) engine.getCustomCut2Kmh())
                .putFloat(KEY_CUSTOM_MAX, (float) engine.getCustomMaxKmh())
                .putFloat(KEY_CUSTOM_ASYNC, (float) engine.getCustomAsyncCarrierHz())
                .putInt(KEY_CUSTOM_SYNC, engine.getCustomSyncPulses())
                .putInt(KEY_CUSTOM_WIDE, engine.getCustomWidePulses())
                .apply();
    }

    private void saveString(String key, String value) {
        if (prefs != null && value != null) prefs.edit().putString(key, value).apply();
    }

    private void saveFloat(String key, float value) {
        if (prefs != null) prefs.edit().putFloat(key, value).apply();
    }

    private void saveBoolean(String key, boolean value) {
        if (prefs != null) prefs.edit().putBoolean(key, value).apply();
    }


    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Miku VVVF Sound",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Speed-bound VVVF / engine sound service");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat_vvvf)
                .setContentTitle("Miku VVVF Fighter HUD")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void broadcastStatus() {
        if (engine == null) return;
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATUS_SPEED, engine.getDisplaySpeedKmh());
        i.putExtra(EXTRA_STATUS_TARGET_SPEED, engine.getTargetSpeedKmh());
        i.putExtra(EXTRA_STATUS_RPM, engine.getDisplayRpm());
        i.putExtra(EXTRA_STATUS_THROTTLE, engine.getDisplayThrottle());
        i.putExtra(EXTRA_STATUS_ACCEL, engine.getDisplayAccel());
        i.putExtra(EXTRA_STATUS_STYLE, engine.getStyle().name());
        i.putExtra(EXTRA_STATUS_STAGE, engine.getStageName());
        i.putExtra(EXTRA_STATUS_SOURCE, engine.getInputSourceName());
        i.putExtra(EXTRA_STATUS_HOOK, hookStatus);
        i.putExtra(EXTRA_STATUS_BACKGROUND_MUTE, engine.isMuteWhenBackground());
        i.putExtra(EXTRA_STATUS_RPM_BINDING, engine.isRpmBindingEnabled());
        i.putExtra(EXTRA_STATUS_EFFECTIVE_MUTE, engine.isEffectivelyMuted());
        i.putExtra(EXTRA_STATUS_CUSTOM_SUMMARY, engine.getCustomVvvfSummary());
        sendBroadcast(i);
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            String text = String.format(Locale.US, "%s · %.1f km/h · %s · %s · vol %.0f%% · UDP %d%s%s",
                    engine.getStyle().name(), engine.getTargetSpeedKmh(), engine.getStageName(), engine.getInputSourceName(),
                    engine.getVolume() * 100f, UDP_PORT, engine.isEffectivelyMuted() ? " · muted" : "",
                    engine.isRpmBindingEnabled() ? " · RPM bind" : "");
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
