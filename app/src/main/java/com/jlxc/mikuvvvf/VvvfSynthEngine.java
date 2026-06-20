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
            smoothedAccel = smoothedAccel * 0.72 + rawAccel * 0.28;
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
        final double smoothAlpha = 1.0 - Math.exp(-1.0 / (SAMPLE_RATE * 0.085));
        Style currentStyle = style;

        for (int i = 0; i < frames; i++) {
            smoothedSpeed += (targetSpeedKmh - smoothedSpeed) * smoothAlpha;
            double speed = smoothedSpeed;

            double motorHz = calcMotorHz(speed, currentStyle);
            double carrierHz = calcCarrierHz(speed, currentStyle);

            motorPhase += TWO_PI * motorHz / SAMPLE_RATE;
            carrierPhase += TWO_PI * carrierHz / SAMPLE_RATE;
            if (motorPhase > TWO_PI) motorPhase -= TWO_PI;
            if (carrierPhase > TWO_PI) carrierPhase -= TWO_PI;

            double gating = 0.52 + 0.48 * Math.abs(Math.sin(motorPhase));
            double motor = Math.sin(motorPhase)
                    + 0.34 * Math.sin(2.0 * motorPhase)
                    + 0.16 * Math.sin(3.0 * motorPhase);

            double wave;
            if (currentStyle == Style.GTO) {
                double squareCarrier = Math.sin(carrierPhase) >= 0 ? 1.0 : -1.0;
                double roughCarrier = squareCarrier * gating;
                double lowPulse = saw(carrierPhase * 0.47) * (0.38 + 0.62 * gating);
                double stepNoise = (random.nextDouble() - 0.5) * 0.035;
                wave = 0.30 * motor + 0.46 * roughCarrier + 0.18 * lowPulse + stepNoise;
            } else {
                double smoothCarrier = Math.sin(carrierPhase + 2.1 * Math.sin(motorPhase));
                double harmonicCarrier = Math.sin(2.0 * carrierPhase + 0.8 * Math.sin(motorPhase));
                double fineNoise = (random.nextDouble() - 0.5) * 0.012;
                wave = 0.38 * motor + 0.38 * smoothCarrier * gating + 0.14 * harmonicCarrier + fineNoise;
            }

            // Acceleration makes traction louder. Braking gets a little metallic regen whine.
            double accel = smoothedAccel;
            double driveGain;
            if (speed < 1.0) {
                driveGain = speed / 1.0;
            } else if (accel > 0.35) {
                driveGain = 0.88 + Math.min(0.32, accel / 45.0);
            } else if (accel < -0.35) {
                double regen = Math.sin(carrierPhase * 0.62 + motorPhase * 1.6);
                wave = wave * 0.72 + regen * 0.35;
                driveGain = 0.72 + Math.min(0.22, -accel / 55.0);
            } else {
                driveGain = 0.34 + Math.min(0.22, speed / 160.0);
            }

            // High speed should not become painfully loud on car speakers.
            double highSpeedDamping = speed > 90.0 ? Math.max(0.55, 1.0 - (speed - 90.0) / 210.0) : 1.0;
            double amp = muted ? 0.0 : volume * 0.42 * driveGain * highSpeedDamping;

            double sample = softClip(wave * amp);
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample * 32767.0));
        }
    }

    private double calcMotorHz(double speed, Style style) {
        double hz = 8.0 + speed * 1.42;
        if (style == Style.GTO && speed < 42.0) {
            // A little step feeling, closer to old GTO traction sound.
            double step = speed < 18.0 ? 2.8 : 4.5;
            hz = Math.round(hz / step) * step;
        }
        return clamp(hz, 0.0, style == Style.GTO ? 185.0 : 230.0);
    }

    private double calcCarrierHz(double speed, Style style) {
        if (style == Style.GTO) {
            if (speed < 4.0) return 220.0 + speed * 45.0;
            if (speed < 16.0) return 420.0 + speed * 33.0;
            if (speed < 36.0) return 760.0 + speed * 25.0;
            if (speed < 70.0) return 1280.0 + speed * 15.0;
            return clamp(2100.0 + speed * 8.5, 2100.0, 3400.0);
        } else {
            return clamp(850.0 + speed * 34.0, 850.0, 5200.0);
        }
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
