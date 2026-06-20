package com.jlxc.mikuvvvf;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class VvvfSynthEngine {
    public enum Style { GTO, IGBT }

    public interface StatusListener {
        void onStatus(String text);
    }

    private static final int SAMPLE_RATE = 48000;
    private static final double TWO_PI = Math.PI * 2.0;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Random random = new Random(20260620L);

    private Thread audioThread;
    private AudioTrack audioTrack;
    private StatusListener statusListener;

    private volatile float targetSpeedKmh = 0f;
    private volatile float volume = 0.55f;
    private volatile Style style = Style.GTO;
    private volatile boolean muted = false;

    private double motorPhase = 0.0;
    private double carrierPhase = 0.0;
    private double subCarrierPhase = 0.0;
    private double transitionPhase = 0.0;
    private double smoothedSpeed = 0.0;
    private double smoothedAccel = 0.0;
    private long lastSpeedSetNs = 0L;
    private float lastSpeedInput = 0f;

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        audioThread = new Thread(this::audioLoop, "MikuVVVF-AudioThread");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
    }

    public void stop() {
        running.set(false);
        if (audioThread != null) {
            audioThread.interrupt();
            try { audioThread.join(500); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
        releaseTrack();
    }

    public boolean isRunning() {
        return running.get();
    }

    public void setSpeedKmh(float speedKmh) {
        if (Float.isNaN(speedKmh) || Float.isInfinite(speedKmh)) return;
        speedKmh = Math.max(0f, Math.min(240f, speedKmh));

        long now = System.nanoTime();
        if (lastSpeedSetNs != 0L) {
            double dt = Math.max(0.02, (now - lastSpeedSetNs) / 1_000_000_000.0);
            double rawAccel = (speedKmh - lastSpeedInput) / dt;
            smoothedAccel = smoothedAccel * 0.68 + rawAccel * 0.32;
        }
        lastSpeedSetNs = now;
        lastSpeedInput = speedKmh;
        targetSpeedKmh = speedKmh;
    }

    public float getTargetSpeedKmh() {
        return targetSpeedKmh;
    }

    public void setVolume(float value) {
        volume = Math.max(0f, Math.min(1f, value));
    }

    public float getVolume() {
        return volume;
    }

    public void setMuted(boolean value) {
        muted = value;
    }

    public boolean isMuted() {
        return muted;
    }

    public void setStyle(Style value) {
        if (value != null) style = value;
    }

    public Style getStyle() {
        return style;
    }

    public String getStageName() {
        return getStageName(targetSpeedKmh, style);
    }

    private void audioLoop() {
        int minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int frames = Math.max(1024, minBuffer / 2);
        short[] buffer = new short[frames];

        try {
            audioTrack = createTrack(Math.max(minBuffer, frames * 2));
            audioTrack.play();
            notifyStatus("AudioTrack started");

            while (running.get()) {
                fillBuffer(buffer, frames);
                audioTrack.write(buffer, 0, frames);
            }
        } catch (Throwable t) {
            notifyStatus("Audio error: " + t.getMessage());
        } finally {
            releaseTrack();
            notifyStatus("AudioTrack stopped");
        }
    }

    private AudioTrack createTrack(int bufferBytes) {
        if (Build.VERSION.SDK_INT >= 23) {
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
        }
        return new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes, AudioTrack.MODE_STREAM);
    }

    private void releaseTrack() {
        if (audioTrack == null) return;
        try { audioTrack.pause(); } catch (Throwable ignored) {}
        try { audioTrack.flush(); } catch (Throwable ignored) {}
        try { audioTrack.release(); } catch (Throwable ignored) {}
        audioTrack = null;
    }

    private void fillBuffer(short[] out, int frames) {
        final double smoothAlpha = 1.0 - Math.exp(-1.0 / (SAMPLE_RATE * 0.080));
        Style currentStyle = style;

        for (int i = 0; i < frames; i++) {
            smoothedSpeed += (targetSpeedKmh - smoothedSpeed) * smoothAlpha;
            smoothedAccel *= 0.999995; // 速度输入停止后，让“加速/制动感”慢慢衰减。

            double speed = smoothedSpeed;
            int stage = calcStage(speed, currentStyle);
            double motorHz = calcMotorHz(speed, currentStyle);
            double carrierHz = calcCarrierHz(speed, currentStyle, motorHz);
            double subCarrierHz = calcSubCarrierHz(speed, currentStyle, motorHz);

            motorPhase += TWO_PI * motorHz / SAMPLE_RATE;
            carrierPhase += TWO_PI * carrierHz / SAMPLE_RATE;
            subCarrierPhase += TWO_PI * subCarrierHz / SAMPLE_RATE;
            if (motorPhase > TWO_PI) motorPhase -= TWO_PI;
            if (carrierPhase > TWO_PI) carrierPhase -= TWO_PI;
            if (subCarrierPhase > TWO_PI) subCarrierPhase -= TWO_PI;

            double gating = 0.50 + 0.50 * Math.abs(Math.sin(motorPhase));
            double motor = Math.sin(motorPhase)
                    + 0.30 * Math.sin(2.0 * motorPhase)
                    + 0.14 * Math.sin(3.0 * motorPhase)
                    + 0.07 * Math.sin(5.0 * motorPhase);

            double wave;
            if (currentStyle == Style.GTO) {
                wave = renderGtoWave(stage, motor, gating);
            } else {
                wave = renderIgbtWave(stage, motor, gating);
            }

            // 在几个速度阈值附近加一点“换段啸叫”，听起来会像进入二阶段/三阶段。
            double transition = currentStyle == Style.GTO
                    ? max3(stagePulse(speed, 8.0, 2.0), stagePulse(speed, 24.0, 2.8),
                           Math.max(stagePulse(speed, 42.0, 3.0), stagePulse(speed, 68.0, 4.0)))
                    : max3(stagePulse(speed, 16.0, 3.0), stagePulse(speed, 36.0, 4.0), stagePulse(speed, 78.0, 6.0));
            double chirpHz = currentStyle == Style.GTO ? 1100.0 + speed * 33.0 : 1800.0 + speed * 42.0;
            transitionPhase += TWO_PI * chirpHz / SAMPLE_RATE;
            if (transitionPhase > TWO_PI) transitionPhase -= TWO_PI;
            wave = wave * (1.0 - 0.18 * transition) + Math.sin(transitionPhase + motorPhase * 0.4) * 0.40 * transition;

            // 加速时牵引声更大，减速时加入偏金属的再生制动声。
            double accel = smoothedAccel;
            double driveGain;
            if (speed < 0.7) {
                driveGain = speed / 0.7;
            } else if (accel > 0.35) {
                driveGain = 0.90 + Math.min(0.36, accel / 42.0);
            } else if (accel < -0.35) {
                double regen = Math.sin(carrierPhase * 0.55 + motorPhase * 1.8)
                        + 0.35 * Math.sin(subCarrierPhase * 0.72);
                wave = wave * 0.66 + regen * 0.34;
                driveGain = 0.72 + Math.min(0.26, -accel / 52.0);
            } else {
                driveGain = 0.32 + Math.min(0.24, speed / 150.0);
            }

            // 高速时不要把车机喇叭吵炸。
            double highSpeedDamping = speed > 92.0 ? Math.max(0.50, 1.0 - (speed - 92.0) / 190.0) : 1.0;
            double amp = muted ? 0.0 : volume * 0.40 * driveGain * highSpeedDamping;

            double sample = softClip(wave * amp);
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample * 32767.0));
        }
    }

    private double renderGtoWave(int stage, double motor, double gating) {
        double squareCarrier = Math.sin(carrierPhase) >= 0 ? 1.0 : -1.0;
        double subSquare = Math.sin(subCarrierPhase) >= 0 ? 1.0 : -1.0;
        double sawCarrier = saw(carrierPhase);
        double roughNoise = (random.nextDouble() - 0.5) * 0.030;

        switch (stage) {
            case 0: // 起步低频脉冲，粗，像老电车刚动起来。
                return 0.22 * motor
                        + 0.48 * squareCarrier * gating
                        + 0.26 * sawCarrier * (0.45 + 0.55 * gating)
                        + roughNoise;
            case 1: // 异步调制，载波明显上扫。
                return 0.26 * motor
                        + 0.52 * squareCarrier * gating
                        + 0.16 * subSquare * (0.35 + 0.65 * gating)
                        + 0.08 * sawCarrier
                        + roughNoise;
            case 2: // 同步一段，声音会突然变得更“锁相”。
                return 0.34 * motor
                        + 0.40 * Math.sin(carrierPhase + 2.2 * Math.sin(motorPhase)) * gating
                        + 0.22 * subSquare
                        + roughNoise * 0.8;
            case 3: // 同步二段，高频更尖，粗糙度下降。
                return 0.30 * motor
                        + 0.48 * Math.sin(carrierPhase + 1.4 * Math.sin(3.0 * motorPhase)) * gating
                        + 0.14 * Math.sin(subCarrierPhase + motorPhase)
                        + roughNoise * 0.55;
            default: // 高速弱磁/巡航段，声音变薄、变高、音量减小。
                return 0.22 * motor
                        + 0.42 * Math.sin(carrierPhase + 0.7 * Math.sin(motorPhase))
                        + 0.18 * Math.sin(1.5 * carrierPhase + 0.5 * motorPhase)
                        + roughNoise * 0.35;
        }
    }

    private double renderIgbtWave(int stage, double motor, double gating) {
        double smoothCarrier = Math.sin(carrierPhase + 1.8 * Math.sin(motorPhase));
        double harmonicCarrier = Math.sin(2.0 * carrierPhase + 0.7 * Math.sin(motorPhase));
        double fineNoise = (random.nextDouble() - 0.5) * 0.010;

        switch (stage) {
            case 0: // 现代 IGBT 低速起步，顺滑但有明显上扬。
                return 0.34 * motor + 0.42 * smoothCarrier * gating + 0.12 * harmonicCarrier + fineNoise;
            case 1: // 中低速异步段。
                return 0.32 * motor + 0.48 * smoothCarrier * gating + 0.16 * harmonicCarrier + fineNoise;
            case 2: // 中速同步段，听感切成更稳定的高频啸叫。
                return 0.30 * motor + 0.46 * Math.sin(carrierPhase + 0.9 * Math.sin(2.0 * motorPhase))
                        + 0.20 * Math.sin(subCarrierPhase + motorPhase) + fineNoise;
            default: // 高速段，变细、变轻。
                return 0.22 * motor + 0.50 * Math.sin(carrierPhase + 0.45 * Math.sin(motorPhase))
                        + 0.12 * harmonicCarrier + fineNoise * 0.6;
        }
    }

    private int calcStage(double speed, Style style) {
        if (style == Style.GTO) {
            if (speed < 8.0) return 0;     // 起步脉冲
            if (speed < 24.0) return 1;    // 异步调制
            if (speed < 42.0) return 2;    // 同步 1 段
            if (speed < 68.0) return 3;    // 同步 2 段
            return 4;                      // 高速弱磁/巡航
        } else {
            if (speed < 16.0) return 0;
            if (speed < 36.0) return 1;
            if (speed < 78.0) return 2;
            return 3;
        }
    }

    private String getStageName(double speed, Style style) {
        int stage = calcStage(speed, style);
        if (style == Style.GTO) {
            switch (stage) {
                case 0: return "起步脉冲";
                case 1: return "异步上扫";
                case 2: return "同步一段";
                case 3: return "同步二段";
                default: return "高速弱磁";
            }
        } else {
            switch (stage) {
                case 0: return "低速顺滑";
                case 1: return "异步上扫";
                case 2: return "同步啸叫";
                default: return "高速轻鸣";
            }
        }
    }

    private double calcMotorHz(double speed, Style style) {
        double hz;
        if (style == Style.GTO) {
            if (speed < 8.0) {
                hz = 8.0 + speed * 1.15;
                hz = quantize(hz, 1.8);
            } else if (speed < 24.0) {
                hz = 17.0 + (speed - 8.0) * 2.35;
                hz = quantize(hz, 3.2);
            } else if (speed < 42.0) {
                hz = 56.0 + (speed - 24.0) * 1.25;
                hz = quantize(hz, 4.6);
            } else if (speed < 68.0) {
                hz = 80.0 + (speed - 42.0) * 0.96;
            } else {
                hz = 105.0 + (speed - 68.0) * 0.58;
            }
            return clamp(hz, 0.0, 190.0);
        } else {
            if (speed < 16.0) hz = 10.0 + speed * 1.70;
            else if (speed < 36.0) hz = 38.0 + (speed - 16.0) * 1.95;
            else if (speed < 78.0) hz = 78.0 + (speed - 36.0) * 1.18;
            else hz = 128.0 + (speed - 78.0) * 0.62;
            return clamp(hz, 0.0, 235.0);
        }
    }

    private double calcCarrierHz(double speed, Style style, double motorHz) {
        if (style == Style.GTO) {
            if (speed < 8.0) {
                return 180.0 + speed * 48.0;
            } else if (speed < 24.0) {
                // 异步调制：载波持续上扫。
                return 520.0 + (speed - 8.0) * 82.0;
            } else if (speed < 42.0) {
                // 同步一段：从连续上扫切到锁相比例，所以听感会“换一档”。
                return clamp(motorHz * 18.0, 980.0, 1650.0);
            } else if (speed < 68.0) {
                // 同步二段：比例再次跳变。
                return clamp(motorHz * 27.0, 2200.0, 3300.0);
            } else {
                return clamp(3150.0 + (speed - 68.0) * 7.5, 3150.0, 4500.0);
            }
        } else {
            if (speed < 16.0) return 760.0 + speed * 48.0;
            if (speed < 36.0) return 1500.0 + (speed - 16.0) * 56.0;
            if (speed < 78.0) return clamp(motorHz * 34.0, 2500.0, 4300.0);
            return clamp(4800.0 + (speed - 78.0) * 6.0, 4800.0, 5800.0);
        }
    }

    private double calcSubCarrierHz(double speed, Style style, double motorHz) {
        if (style == Style.GTO) {
            int stage = calcStage(speed, style);
            if (stage <= 1) return 110.0 + speed * 13.0;
            if (stage == 2) return motorHz * 9.0;
            if (stage == 3) return motorHz * 13.5;
            return motorHz * 18.0;
        } else {
            if (speed < 36.0) return 360.0 + speed * 20.0;
            return motorHz * 17.0;
        }
    }

    private static double stagePulse(double speed, double center, double width) {
        double d = Math.abs(speed - center);
        if (d >= width) return 0.0;
        double x = 1.0 - d / width;
        return x * x * (3.0 - 2.0 * x);
    }

    private static double max3(double a, double b, double c) {
        return Math.max(a, Math.max(b, c));
    }

    private static double quantize(double value, double step) {
        return Math.round(value / step) * step;
    }

    private static double saw(double phase) {
        double x = phase / TWO_PI;
        x = x - Math.floor(x);
        return 2.0 * x - 1.0;
    }

    private static double softClip(double x) {
        return Math.tanh(x * 1.25);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private void notifyStatus(String text) {
        StatusListener listener = statusListener;
        if (listener != null) listener.onStatus(String.format(Locale.US, "%s", text));
    }
}
