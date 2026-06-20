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
    public enum Style {
        GTO,
        IGBT,
        SIEMENS_GZ_GTO,
        AIRCRAFT_TURBINE,
        POP_BANG_TURBO,
        NATURAL_ASPIRATED,
        ROTARY,
        SUPERCHARGED_V8
    }

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
    private volatile Style style = Style.SIEMENS_GZ_GTO;
    private volatile boolean muted = false;

    private double motorPhase = 0.0;
    private double carrierPhase = 0.0;
    private double subCarrierPhase = 0.0;
    private double transitionPhase = 0.0;
    private double smoothedSpeed = 0.0;
    private double smoothedAccel = 0.0;
    private double popEnvelope = 0.0;
    private double shiftEnvelope = 0.0;
    private int lastRenderedStage = -100;
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
        if (value != null) {
            style = value;
            lastRenderedStage = -100;
            shiftEnvelope = 0.0;
            popEnvelope = 0.0;
        }
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
            if (stage != lastRenderedStage) {
                if (lastRenderedStage != -100) {
                    shiftEnvelope = Math.max(shiftEnvelope, isRailStyle(currentStyle) ? 0.65 : 0.82);
                    if (currentStyle == Style.POP_BANG_TURBO || currentStyle == Style.SUPERCHARGED_V8) {
                        popEnvelope = Math.max(popEnvelope, 0.90);
                    }
                }
                lastRenderedStage = stage;
            }

            double wave;
            double amp;

            if (isRailStyle(currentStyle)) {
                double motorHz = calcMotorHz(speed, currentStyle);
                double carrierHz = calcCarrierHz(speed, currentStyle, motorHz);
                double subCarrierHz = calcSubCarrierHz(speed, currentStyle, motorHz);

                advancePhases(motorHz, carrierHz, subCarrierHz);
                wave = renderRailWave(currentStyle, stage, speed);
                wave = addRailTransition(currentStyle, speed, wave);

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
                double highSpeedDamping = speed > 92.0 ? Math.max(0.50, 1.0 - (speed - 92.0) / 190.0) : 1.0;
                amp = muted ? 0.0 : volume * 0.40 * driveGain * highSpeedDamping;
            } else {
                double rpm = calcVirtualRpm(speed, currentStyle);
                double baseHz = calcTrafficBaseHz(speed, currentStyle, rpm);
                double whineHz = calcTrafficWhineHz(speed, currentStyle, rpm);
                double pulseHz = calcTrafficPulseHz(speed, currentStyle, rpm);
                advancePhases(baseHz, whineHz, pulseHz);
                transitionPhase += TWO_PI * (14.0 + speed * 0.65) / SAMPLE_RATE;
                if (transitionPhase > TWO_PI) transitionPhase %= TWO_PI;

                wave = renderTrafficWave(currentStyle, stage, speed, rpm, smoothedAccel);

                double accel = smoothedAccel;
                double driveGain;
                if (currentStyle == Style.AIRCRAFT_TURBINE) {
                    driveGain = 0.30 + clamp(speed / 155.0, 0.0, 0.78) + Math.max(0.0, Math.min(0.16, accel / 80.0));
                } else if (speed < 0.5) {
                    driveGain = 0.33;
                } else if (accel > 0.45) {
                    driveGain = 0.78 + Math.min(0.28, accel / 55.0);
                } else if (accel < -0.45) {
                    driveGain = 0.56 + Math.min(0.22, -accel / 70.0);
                } else {
                    driveGain = 0.42 + Math.min(0.22, speed / 180.0);
                }
                amp = muted ? 0.0 : volume * 0.52 * driveGain;
            }

            shiftEnvelope *= 0.99955;
            popEnvelope *= 0.99925;
            double sample = softClip(wave * amp);
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample * 32767.0));
        }
    }

    private void advancePhases(double motorHz, double carrierHz, double subCarrierHz) {
        motorPhase += TWO_PI * motorHz / SAMPLE_RATE;
        carrierPhase += TWO_PI * carrierHz / SAMPLE_RATE;
        subCarrierPhase += TWO_PI * subCarrierHz / SAMPLE_RATE;
        if (motorPhase > TWO_PI) motorPhase %= TWO_PI;
        if (carrierPhase > TWO_PI) carrierPhase %= TWO_PI;
        if (subCarrierPhase > TWO_PI) subCarrierPhase %= TWO_PI;
    }

    private boolean isRailStyle(Style s) {
        return s == Style.GTO || s == Style.IGBT || s == Style.SIEMENS_GZ_GTO;
    }

    private double renderRailWave(Style currentStyle, int stage, double speed) {
        double gating = 0.50 + 0.50 * Math.abs(Math.sin(motorPhase));
        double motor = Math.sin(motorPhase)
                + 0.30 * Math.sin(2.0 * motorPhase)
                + 0.14 * Math.sin(3.0 * motorPhase)
                + 0.07 * Math.sin(5.0 * motorPhase);

        if (currentStyle == Style.SIEMENS_GZ_GTO) {
            return renderGuangzhouSiemensGtoWave(stage, speed, motor, gating);
        } else if (currentStyle == Style.GTO) {
            return renderGtoWave(stage, motor, gating);
        } else {
            return renderIgbtWave(stage, motor, gating);
        }
    }

    private double addRailTransition(Style currentStyle, double speed, double wave) {
        double transition;
        if (currentStyle == Style.SIEMENS_GZ_GTO) {
            transition = max5(stagePulse(speed, 5.5, 1.4), stagePulse(speed, 18.0, 2.3),
                    stagePulse(speed, 32.0, 2.5), stagePulse(speed, 52.0, 3.5), stagePulse(speed, 78.0, 5.0));
        } else if (currentStyle == Style.GTO) {
            transition = max3(stagePulse(speed, 8.0, 2.0), stagePulse(speed, 24.0, 2.8),
                    Math.max(stagePulse(speed, 42.0, 3.0), stagePulse(speed, 68.0, 4.0)));
        } else {
            transition = max3(stagePulse(speed, 16.0, 3.0), stagePulse(speed, 36.0, 4.0), stagePulse(speed, 78.0, 6.0));
        }
        transition = Math.max(transition, shiftEnvelope * 0.6);
        double chirpHz = currentStyle == Style.SIEMENS_GZ_GTO ? 520.0 + speed * 31.0
                : (currentStyle == Style.GTO ? 1100.0 + speed * 33.0 : 1800.0 + speed * 42.0);
        transitionPhase += TWO_PI * chirpHz / SAMPLE_RATE;
        if (transitionPhase > TWO_PI) transitionPhase %= TWO_PI;
        return wave * (1.0 - 0.18 * transition) + Math.sin(transitionPhase + motorPhase * 0.4) * 0.40 * transition;
    }

    private double renderGuangzhouSiemensGtoWave(int stage, double speed, double motor, double gating) {
        // 目标听感：广州地铁 1 号线 A1 / Adtranz-Siemens GTO-VVVF 那类“地铁味”。
        // 重点不是线性升频，而是低速断续脉冲、中速阶梯音阶、高速锁相啸叫。
        double squareCarrier = Math.sin(carrierPhase) >= 0 ? 1.0 : -1.0;
        double subSquare = Math.sin(subCarrierPhase) >= 0 ? 1.0 : -1.0;
        double sineCarrier = Math.sin(carrierPhase + 1.15 * Math.sin(motorPhase));
        double sineSub = Math.sin(subCarrierPhase + 0.65 * Math.sin(2.0 * motorPhase));
        double rumble = Math.sin(motorPhase * 0.48) + 0.30 * Math.sin(motorPhase * 0.96);
        double railNoise = (random.nextDouble() - 0.5) * (0.018 + Math.min(0.030, speed / 2600.0));

        switch (stage) {
            case 0:
                return 0.18 * motor + 0.42 * squareCarrier * (0.30 + 0.70 * gating)
                        + 0.24 * saw(carrierPhase) * gating + 0.20 * rumble + railNoise;
            case 1:
                return 0.22 * motor + 0.48 * squareCarrier * gating + 0.22 * sineSub
                        + 0.12 * saw(carrierPhase) + 0.10 * rumble + railNoise;
            case 2:
                return 0.28 * motor + 0.44 * Math.sin(carrierPhase + 2.8 * Math.sin(motorPhase)) * gating
                        + 0.25 * subSquare * (0.45 + 0.55 * gating) + 0.08 * rumble + railNoise * 0.85;
            case 3:
                return 0.26 * motor + 0.50 * Math.sin(carrierPhase + 1.9 * Math.sin(2.0 * motorPhase)) * gating
                        + 0.18 * Math.sin(1.5 * carrierPhase + motorPhase) + 0.11 * subSquare + railNoise * 0.70;
            case 4:
                return 0.20 * motor + 0.55 * sineCarrier + 0.22 * Math.sin(2.0 * carrierPhase + 0.5 * motorPhase)
                        + 0.08 * sineSub + railNoise * 0.55;
            default:
                return 0.15 * motor + 0.46 * Math.sin(carrierPhase + 0.35 * Math.sin(motorPhase))
                        + 0.18 * Math.sin(1.75 * carrierPhase) + 0.06 * rumble + railNoise * 0.42;
        }
    }

    private double renderGtoWave(int stage, double motor, double gating) {
        double squareCarrier = Math.sin(carrierPhase) >= 0 ? 1.0 : -1.0;
        double subSquare = Math.sin(subCarrierPhase) >= 0 ? 1.0 : -1.0;
        double sawCarrier = saw(carrierPhase);
        double roughNoise = (random.nextDouble() - 0.5) * 0.030;

        switch (stage) {
            case 0:
                return 0.22 * motor + 0.48 * squareCarrier * gating + 0.26 * sawCarrier * (0.45 + 0.55 * gating) + roughNoise;
            case 1:
                return 0.26 * motor + 0.52 * squareCarrier * gating + 0.16 * subSquare * (0.35 + 0.65 * gating)
                        + 0.08 * sawCarrier + roughNoise;
            case 2:
                return 0.34 * motor + 0.40 * Math.sin(carrierPhase + 2.2 * Math.sin(motorPhase)) * gating
                        + 0.22 * subSquare + roughNoise * 0.8;
            case 3:
                return 0.30 * motor + 0.48 * Math.sin(carrierPhase + 1.4 * Math.sin(3.0 * motorPhase)) * gating
                        + 0.14 * Math.sin(subCarrierPhase + motorPhase) + roughNoise * 0.55;
            default:
                return 0.22 * motor + 0.42 * Math.sin(carrierPhase + 0.7 * Math.sin(motorPhase))
                        + 0.18 * Math.sin(1.5 * carrierPhase + 0.5 * motorPhase) + roughNoise * 0.35;
        }
    }

    private double renderIgbtWave(int stage, double motor, double gating) {
        double smoothCarrier = Math.sin(carrierPhase + 1.8 * Math.sin(motorPhase));
        double harmonicCarrier = Math.sin(2.0 * carrierPhase + 0.7 * Math.sin(motorPhase));
        double fineNoise = (random.nextDouble() - 0.5) * 0.010;

        switch (stage) {
            case 0:
                return 0.34 * motor + 0.42 * smoothCarrier * gating + 0.12 * harmonicCarrier + fineNoise;
            case 1:
                return 0.32 * motor + 0.48 * smoothCarrier * gating + 0.16 * harmonicCarrier + fineNoise;
            case 2:
                return 0.30 * motor + 0.46 * Math.sin(carrierPhase + 0.9 * Math.sin(2.0 * motorPhase))
                        + 0.20 * Math.sin(subCarrierPhase + motorPhase) + fineNoise;
            default:
                return 0.22 * motor + 0.50 * Math.sin(carrierPhase + 0.45 * Math.sin(motorPhase))
                        + 0.12 * harmonicCarrier + fineNoise * 0.6;
        }
    }

    private double renderTrafficWave(Style currentStyle, int stage, double speed, double rpm, double accel) {
        switch (currentStyle) {
            case AIRCRAFT_TURBINE:
                return renderAircraftTurbine(speed, accel);
            case POP_BANG_TURBO:
                return renderPopBangTurbo(stage, speed, rpm, accel);
            case NATURAL_ASPIRATED:
                return renderNaturalAspirated(stage, rpm, accel);
            case ROTARY:
                return renderRotary(stage, rpm, accel);
            case SUPERCHARGED_V8:
                return renderSuperchargedV8(stage, speed, rpm, accel);
            default:
                return 0.0;
        }
    }

    private double renderAircraftTurbine(double speed, double accel) {
        double spool = clamp(speed / 150.0 + Math.max(0.0, accel) / 240.0, 0.0, 1.0);
        double fan = Math.sin(motorPhase) + 0.35 * Math.sin(2.0 * motorPhase) + 0.18 * Math.sin(3.0 * motorPhase);
        double turbineWhine = Math.sin(carrierPhase + 0.20 * Math.sin(motorPhase))
                + 0.28 * Math.sin(1.52 * carrierPhase);
        double rumble = Math.sin(subCarrierPhase) + 0.45 * Math.sin(0.52 * subCarrierPhase);
        double air = (random.nextDouble() - 0.5) * (0.10 + 0.26 * spool);
        double compressor = Math.sin(carrierPhase * 0.48 + Math.sin(motorPhase) * 0.40);
        return 0.18 * rumble + 0.34 * fan + (0.22 + 0.38 * spool) * turbineWhine
                + 0.18 * compressor + air;
    }

    private double renderPopBangTurbo(int stage, double speed, double rpm, double accel) {
        if (accel < -0.45 && random.nextDouble() < 0.00010) popEnvelope = Math.max(popEnvelope, 1.0);
        if (shiftEnvelope > 0.45 && random.nextDouble() < 0.00018) popEnvelope = Math.max(popEnvelope, 0.8);

        double exhaust = harshPulse(subCarrierPhase, 3.2)
                + 0.42 * harshPulse(subCarrierPhase + 1.7, 5.0)
                + 0.18 * Math.sin(motorPhase * 0.50);
        double turboWhistle = Math.sin(carrierPhase + 0.15 * Math.sin(motorPhase)) * clamp((rpm - 1700.0) / 4300.0, 0.0, 1.0);
        double wastegate = Math.sin(0.72 * carrierPhase + 1.6 * Math.sin(motorPhase));
        double crackleNoise = (random.nextDouble() - 0.5) * (1.2 * popEnvelope + 0.035);
        double popBoom = popEnvelope * (0.70 * Math.sin(motorPhase * 0.20) + 0.45 * crackleNoise);
        double shiftCut = shiftEnvelope * (random.nextDouble() - 0.5) * 0.35;
        double gearGrowl = 0.12 * stage * Math.sin(motorPhase * 0.25);
        return 0.54 * exhaust + 0.28 * turboWhistle + 0.12 * wastegate + 0.28 * popBoom + shiftCut + gearGrowl;
    }

    private double renderNaturalAspirated(int stage, double rpm, double accel) {
        double rev = clamp((rpm - 900.0) / 6800.0, 0.0, 1.0);
        double exhaust = Math.sin(subCarrierPhase) + 0.35 * Math.sin(2.0 * subCarrierPhase)
                + 0.16 * Math.sin(3.0 * subCarrierPhase);
        double intake = Math.sin(carrierPhase + 0.55 * Math.sin(motorPhase)) * (0.15 + 0.65 * rev);
        double mechanical = 0.11 * Math.sin(5.0 * motorPhase) + 0.06 * saw(motorPhase * 1.5);
        double lift = Math.max(0.0, accel) * 0.004;
        double gearTone = 0.04 * stage * Math.sin(motorPhase * 0.33);
        return 0.52 * exhaust + 0.34 * intake + 0.18 * mechanical + 0.10 * lift * Math.sin(carrierPhase * 1.3) + gearTone;
    }

    private double renderRotary(int stage, double rpm, double accel) {
        double rev = clamp((rpm - 1200.0) / 7800.0, 0.0, 1.0);
        double buzz = Math.sin(subCarrierPhase) + 0.55 * Math.sin(2.0 * subCarrierPhase)
                + 0.32 * Math.sin(3.0 * subCarrierPhase) + 0.17 * Math.sin(5.0 * subCarrierPhase);
        double smoothHowl = Math.sin(carrierPhase + 0.8 * Math.sin(motorPhase)) * (0.25 + 0.62 * rev);
        double portNoise = (random.nextDouble() - 0.5) * (0.025 + 0.045 * rev);
        double shiftBleep = shiftEnvelope * 0.32 * Math.sin(carrierPhase * 1.6);
        double stageRing = 0.05 * stage * Math.sin(motorPhase * 0.42);
        return 0.46 * buzz + 0.38 * smoothHowl + 0.12 * Math.sin(9.0 * motorPhase) + portNoise + shiftBleep + stageRing;
    }

    private double renderSuperchargedV8(int stage, double speed, double rpm, double accel) {
        if (accel < -0.55 && random.nextDouble() < 0.000035) popEnvelope = Math.max(popEnvelope, 0.55);
        double rev = clamp((rpm - 800.0) / 5400.0, 0.0, 1.0);
        double v8Pulse = harshPulse(subCarrierPhase, 2.4)
                + 0.50 * harshPulse(subCarrierPhase + 0.72, 3.6)
                + 0.22 * Math.sin(subCarrierPhase * 0.5);
        double blowerWhine = Math.sin(carrierPhase + 0.12 * Math.sin(motorPhase)) * (0.18 + 0.88 * rev);
        double lowRumble = Math.sin(motorPhase * 0.24) + 0.38 * Math.sin(motorPhase * 0.48);
        double roadLoad = 0.05 * Math.sin((30.0 + speed * 0.7) * transitionPhase);
        double pop = popEnvelope * ((random.nextDouble() - 0.5) * 0.7 + 0.30 * Math.sin(motorPhase * 0.18));
        double shiftWhump = shiftEnvelope * 0.22 * Math.sin(motorPhase * 0.18);
        return 0.42 * v8Pulse + 0.34 * blowerWhine + 0.30 * lowRumble + 0.14 * pop + shiftWhump + roadLoad;
    }

    private int calcStage(double speed, Style style) {
        if (style == Style.SIEMENS_GZ_GTO) {
            if (speed < 5.5) return 0;
            if (speed < 18.0) return 1;
            if (speed < 32.0) return 2;
            if (speed < 52.0) return 3;
            if (speed < 78.0) return 4;
            return 5;
        } else if (style == Style.GTO) {
            if (speed < 8.0) return 0;
            if (speed < 24.0) return 1;
            if (speed < 42.0) return 2;
            if (speed < 68.0) return 3;
            return 4;
        } else if (style == Style.IGBT) {
            if (speed < 16.0) return 0;
            if (speed < 36.0) return 1;
            if (speed < 78.0) return 2;
            return 3;
        } else if (style == Style.AIRCRAFT_TURBINE) {
            if (speed < 18.0) return 0;
            if (speed < 55.0) return 1;
            if (speed < 115.0) return 2;
            return 3;
        } else {
            return calcVirtualGear(speed);
        }
    }

    private String getStageName(double speed, Style style) {
        int stage = calcStage(speed, style);
        if (style == Style.SIEMENS_GZ_GTO) {
            switch (stage) {
                case 0: return "广铁西门子·起步敲击";
                case 1: return "广铁西门子·一段音阶";
                case 2: return "广铁西门子·二阶段锁相";
                case 3: return "广铁西门子·三阶段啸叫";
                case 4: return "广铁西门子·高速同步";
                default: return "广铁西门子·高速弱磁";
            }
        } else if (style == Style.GTO) {
            switch (stage) {
                case 0: return "起步脉冲";
                case 1: return "异步上扫";
                case 2: return "同步一段";
                case 3: return "同步二段";
                default: return "高速弱磁";
            }
        } else if (style == Style.IGBT) {
            switch (stage) {
                case 0: return "低速顺滑";
                case 1: return "异步上扫";
                case 2: return "同步啸叫";
                default: return "高速轻鸣";
            }
        } else if (style == Style.AIRCRAFT_TURBINE) {
            switch (stage) {
                case 0: return "飞机·滑行低推";
                case 1: return "飞机·涡扇上转";
                case 2: return "飞机·起飞推力";
                default: return "飞机·高速巡航";
            }
        } else {
            return getStyleShortName(style) + "·虚拟" + (stage + 1) + "挡";
        }
    }

    private String getStyleShortName(Style style) {
        switch (style) {
            case POP_BANG_TURBO: return "偏时点火";
            case NATURAL_ASPIRATED: return "自然吸气";
            case ROTARY: return "转子";
            case SUPERCHARGED_V8: return "机械增压V8";
            case AIRCRAFT_TURBINE: return "飞机";
            case SIEMENS_GZ_GTO: return "广铁西门子";
            case IGBT: return "IGBT";
            default: return "GTO";
        }
    }

    private double calcMotorHz(double speed, Style style) {
        double hz;
        if (style == Style.SIEMENS_GZ_GTO) {
            if (speed < 5.5) {
                hz = quantize(7.0 + speed * 1.35, 1.3);
            } else if (speed < 18.0) {
                hz = quantize(15.0 + (speed - 5.5) * 2.75, 2.7);
            } else if (speed < 32.0) {
                hz = quantize(49.0 + (speed - 18.0) * 1.95, 3.8);
            } else if (speed < 52.0) {
                hz = quantize(78.0 + (speed - 32.0) * 1.20, 5.2);
            } else if (speed < 78.0) {
                hz = 103.0 + (speed - 52.0) * 0.86;
            } else {
                hz = 126.0 + (speed - 78.0) * 0.45;
            }
            return clamp(hz, 0.0, 185.0);
        } else if (style == Style.GTO) {
            if (speed < 8.0) hz = quantize(8.0 + speed * 1.15, 1.8);
            else if (speed < 24.0) hz = quantize(17.0 + (speed - 8.0) * 2.35, 3.2);
            else if (speed < 42.0) hz = quantize(56.0 + (speed - 24.0) * 1.25, 4.6);
            else if (speed < 68.0) hz = 80.0 + (speed - 42.0) * 0.96;
            else hz = 105.0 + (speed - 68.0) * 0.58;
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
        if (style == Style.SIEMENS_GZ_GTO) {
            if (speed < 5.5) {
                return quantize(120.0 + speed * 38.0, 24.0);
            } else if (speed < 18.0) {
                double[] notes = {300.0, 360.0, 430.0, 520.0, 620.0, 740.0, 880.0};
                double pos = (speed - 5.5) / 1.85;
                int idx = (int) clamp(Math.floor(pos), 0, notes.length - 1);
                double glide = pos - Math.floor(pos);
                double next = notes[Math.min(notes.length - 1, idx + 1)];
                return notes[idx] * (1.0 - glide * 0.28) + next * (glide * 0.28);
            } else if (speed < 32.0) {
                return clamp(motorHz * 15.0, 760.0, 1280.0);
            } else if (speed < 52.0) {
                return clamp(motorHz * 23.5, 1450.0, 2350.0);
            } else if (speed < 78.0) {
                return clamp(motorHz * 31.0, 2600.0, 3850.0);
            } else {
                return clamp(3750.0 + (speed - 78.0) * 5.2, 3750.0, 4700.0);
            }
        } else if (style == Style.GTO) {
            if (speed < 8.0) return 180.0 + speed * 48.0;
            if (speed < 24.0) return 520.0 + (speed - 8.0) * 82.0;
            if (speed < 42.0) return clamp(motorHz * 18.0, 980.0, 1650.0);
            if (speed < 68.0) return clamp(motorHz * 27.0, 2200.0, 3300.0);
            return clamp(3150.0 + (speed - 68.0) * 7.5, 3150.0, 4500.0);
        } else {
            if (speed < 16.0) return 760.0 + speed * 48.0;
            if (speed < 36.0) return 1500.0 + (speed - 16.0) * 56.0;
            if (speed < 78.0) return clamp(motorHz * 34.0, 2500.0, 4300.0);
            return clamp(4800.0 + (speed - 78.0) * 6.0, 4800.0, 5800.0);
        }
    }

    private double calcSubCarrierHz(double speed, Style style, double motorHz) {
        if (style == Style.SIEMENS_GZ_GTO) {
            int stage = calcStage(speed, style);
            if (stage == 0) return 85.0 + speed * 9.0;
            if (stage == 1) return quantize(180.0 + speed * 21.0, 30.0);
            if (stage == 2) return motorHz * 7.5;
            if (stage == 3) return motorHz * 11.8;
            if (stage == 4) return motorHz * 16.0;
            return motorHz * 20.0;
        } else if (style == Style.GTO) {
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

    private double calcTrafficBaseHz(double speed, Style style, double rpm) {
        if (style == Style.AIRCRAFT_TURBINE) return 38.0 + clamp(speed, 0.0, 220.0) * 1.55;
        return rpm / 60.0;
    }

    private double calcTrafficWhineHz(double speed, Style style, double rpm) {
        switch (style) {
            case AIRCRAFT_TURBINE:
                return 900.0 + clamp(speed, 0.0, 220.0) * 23.0;
            case POP_BANG_TURBO:
                return clamp(420.0 + rpm * 0.46 + Math.max(0.0, smoothedAccel) * 18.0, 550.0, 5200.0);
            case NATURAL_ASPIRATED:
                return clamp(550.0 + rpm * 0.54, 900.0, 5900.0);
            case ROTARY:
                return clamp(760.0 + rpm * 0.62, 1100.0, 6900.0);
            case SUPERCHARGED_V8:
                return clamp(620.0 + rpm * 0.62, 850.0, 5400.0);
            default:
                return 1000.0;
        }
    }

    private double calcTrafficPulseHz(double speed, Style style, double rpm) {
        double crankHz = rpm / 60.0;
        switch (style) {
            case AIRCRAFT_TURBINE:
                return 24.0 + clamp(speed, 0.0, 220.0) * 0.62;
            case POP_BANG_TURBO:
                return crankHz * 2.0; // 4 缸四冲程近似。
            case NATURAL_ASPIRATED:
                return crankHz * 3.0; // 高转自然吸气 6 缸近似。
            case ROTARY:
                return crankHz * 3.0;
            case SUPERCHARGED_V8:
                return crankHz * 4.0; // V8 四冲程近似。
            default:
                return crankHz;
        }
    }

    private double calcVirtualRpm(double speed, Style style) {
        if (style == Style.AIRCRAFT_TURBINE) return 0.0;
        double idle;
        double redline;
        switch (style) {
            case POP_BANG_TURBO:
                idle = 950.0; redline = 6900.0; break;
            case NATURAL_ASPIRATED:
                idle = 850.0; redline = 8200.0; break;
            case ROTARY:
                idle = 1200.0; redline = 9300.0; break;
            case SUPERCHARGED_V8:
                idle = 720.0; redline = 6250.0; break;
            default:
                idle = 850.0; redline = 7000.0; break;
        }

        double[] maxSpeeds = {36.0, 66.0, 102.0, 142.0, 188.0, 240.0};
        int gear = calcVirtualGear(speed);
        double prev = gear == 0 ? 0.0 : maxSpeeds[gear - 1];
        double next = maxSpeeds[Math.min(gear, maxSpeeds.length - 1)];
        double t = clamp((speed - prev) / Math.max(1.0, next - prev), 0.0, 1.0);
        double rpm = idle + Math.pow(t, 0.72) * (redline - idle);
        rpm += Math.max(-360.0, Math.min(520.0, smoothedAccel * 20.0));
        if (speed < 0.5) rpm = idle + 70.0 * Math.sin(transitionPhase * 0.25);
        return clamp(rpm, idle * 0.85, redline + 500.0);
    }

    private int calcVirtualGear(double speed) {
        if (speed < 36.0) return 0;
        if (speed < 66.0) return 1;
        if (speed < 102.0) return 2;
        if (speed < 142.0) return 3;
        if (speed < 188.0) return 4;
        return 5;
    }

    private static double harshPulse(double phase, double sharpness) {
        double s = Math.sin(phase);
        double shaped = Math.signum(s) * Math.pow(Math.abs(s), 0.36);
        return Math.tanh(shaped * sharpness);
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

    private static double max5(double a, double b, double c, double d, double e) {
        return Math.max(Math.max(a, b), Math.max(Math.max(c, d), e));
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
