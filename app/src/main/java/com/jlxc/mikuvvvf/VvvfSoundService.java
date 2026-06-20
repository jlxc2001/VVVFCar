package com.jlxc.mikuvvvf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

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

    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_STYLE = "style";
    public static final String EXTRA_VOLUME = "volume";
    public static final String EXTRA_MUTE = "mute";

    public static final int UDP_PORT = 47230;

    private static final String CHANNEL_ID = "miku_vvvf_sound";
    private static final int NOTIFICATION_ID = 3939;

    private VvvfSynthEngine engine;
    private final AtomicBoolean udpRunning = new AtomicBoolean(false);
    private Thread udpThread;
    private DatagramSocket udpSocket;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        engine = new VvvfSynthEngine(getApplicationContext());
        engine.setStatusListener(text -> {});
        startForeground(NOTIFICATION_ID, buildNotification("Ready · UDP " + UDP_PORT + " · " + engine.getSampleVvvfStatus()));
        startUdpServer();
        engine.start();
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
                updateNotification();
            } else if (ACTION_SET_VOLUME.equals(action)) {
                engine.setVolume(intent.getFloatExtra(EXTRA_VOLUME, engine.getVolume()));
                updateNotification();
            } else if (ACTION_SET_MUTE.equals(action)) {
                engine.setMuted(intent.getBooleanExtra(EXTRA_MUTE, engine.isMuted()));
                updateNotification();
            } else {
                updateNotification();
            }
        }
        if (!engine.isRunning()) engine.start();
        if (!udpRunning.get()) startUdpServer();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopUdpServer();
        engine.stop();
        super.onDestroy();
    }

    private void stopSelfSafely() {
        stopUdpServer();
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
                    if (parts.length >= 2) applyStyle(parts[1]);
                    break;
                case "VOLUME":
                    if (parts.length >= 2) engine.setVolume(Float.parseFloat(parts[1]));
                    break;
                case "MUTE":
                    engine.setMuted(parts.length < 2 || !"0".equals(parts[1]));
                    break;
                case "UNMUTE":
                    engine.setMuted(false);
                    break;
                case "PING":
                    replyUdp("PONG MIKU_VVVF " + engine.getTargetSpeedKmh(), remote, remotePort);
                    break;
                case "STOP":
                    engine.setSpeedKmh(0f);
                    engine.setMuted(true);
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
        if (s.contains("SAMPLE") || s.contains("REAL") || s.contains("VVVF_SAMPLE") || s.contains("真实") || s.contains("采样") || s.contains("录音")) {
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
                .setContentTitle("Miku VVVF Sample / Engine Sound")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            String text = String.format(Locale.US, "%s · %.1f km/h · %s · vol %.0f%% · UDP %d%s",
                    engine.getStyle().name(), engine.getTargetSpeedKmh(), engine.getStageName(),
                    engine.getVolume() * 100f, UDP_PORT, engine.isMuted() ? " · muted" : "");
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
