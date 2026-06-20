package com.jlxc.mikuvvvf;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int ID_SAMPLE = 1000;
    private static final int ID_GTO = 1001;
    private static final int ID_IGBT = 1002;
    private static final int ID_SIEMENS = 1003;
    private static final int ID_AIRCRAFT = 1004;
    private static final int ID_POP_BANG = 1005;
    private static final int ID_NA = 1006;
    private static final int ID_ROTARY = 1007;
    private static final int ID_SUPERCHARGED_V8 = 1008;

    private TextView speedText;
    private TextView infoText;
    private SeekBar speedSeek;
    private SeekBar volumeSeek;
    private Switch muteSwitch;
    private RadioButton sampleButton;
    private RadioButton gtoButton;
    private RadioButton igbtButton;
    private RadioButton siemensButton;
    private RadioButton aircraftButton;
    private RadioButton popBangButton;
    private RadioButton naButton;
    private RadioButton rotaryButton;
    private RadioButton superchargedV8Button;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean autoTest = false;
    private float autoSpeed = 0f;
    private int autoDirection = 1;

    private final Runnable autoRunnable = new Runnable() {
        @Override public void run() {
            if (!autoTest) return;
            autoSpeed += autoDirection * 0.10f; // V6：按 0→140km/h 样本节奏慢慢爬升，方便听真实换段。
            if (autoSpeed >= 140f) { autoSpeed = 140f; autoDirection = -1; }
            if (autoSpeed <= 0f) { autoSpeed = 0f; autoDirection = 1; }
            speedSeek.setProgress(Math.round(autoSpeed * 10f));
            sendSpeed(autoSpeed);
            handler.postDelayed(this, 90);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        maybeRequestNotificationPermission();
        setContentView(buildContentView());
        startSoundService();
        updateInfoText();
    }

    @Override
    protected void onDestroy() {
        autoTest = false;
        handler.removeCallbacks(autoRunnable);
        super.onDestroy();
    }

    private View buildContentView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Miku VVVF / Engine Sound Demo V6");
        title.setTextSize(25);
        title.setTextColor(Color.rgb(0, 120, 130));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView sub = new TextView(this);
        sub.setText("车速绑定声浪模拟 V6 · 真实 VVVF 采样版 + Engine-Sim 思路 · UDP 47230");
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(6), 0, dp(12));
        root.addView(sub, matchWrap());

        speedText = new TextView(this);
        speedText.setTextSize(42);
        speedText.setGravity(Gravity.CENTER_HORIZONTAL);
        speedText.setText("0.0 km/h");
        speedText.setTextColor(Color.BLACK);
        root.addView(speedText, matchWrap());

        speedSeek = new SeekBar(this);
        speedSeek.setMax(2400); // 0.0 - 240.0 km/h
        speedSeek.setProgress(0);
        speedSeek.setPadding(0, dp(8), 0, dp(8));
        root.addView(speedSeek, matchWrap());
        speedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float speed = progress / 10f;
                speedText.setText(String.format(Locale.US, "%.1f km/h", speed));
                if (fromUser) {
                    autoTest = false;
                    sendSpeed(speed);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        LinearLayout buttons1 = new LinearLayout(this);
        buttons1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons1, matchWrap());

        Button start = new Button(this);
        start.setText("启动服务");
        buttons1.addView(start, weightWrap(1));
        start.setOnClickListener(v -> startSoundService());

        Button stop = new Button(this);
        stop.setText("停止服务");
        buttons1.addView(stop, weightWrap(1));
        stop.setOnClickListener(v -> {
            autoTest = false;
            Intent i = new Intent(this, VvvfSoundService.class);
            i.setAction(VvvfSoundService.ACTION_STOP);
            startServiceCompat(i);
        });

        Button zero = new Button(this);
        zero.setText("归零");
        buttons1.addView(zero, weightWrap(1));
        zero.setOnClickListener(v -> {
            autoTest = false;
            speedSeek.setProgress(0);
            sendSpeed(0f);
        });

        Button auto = new Button(this);
        auto.setText("真实采样自动 0→140→0 测试");
        root.addView(auto, matchWrap());
        auto.setOnClickListener(v -> {
            autoTest = !autoTest;
            if (autoTest) {
                autoSpeed = speedSeek.getProgress() / 10f;
                autoDirection = autoSpeed >= 140f ? -1 : 1;
                handler.removeCallbacks(autoRunnable);
                handler.post(autoRunnable);
                auto.setText("停止自动测试");
            } else {
                handler.removeCallbacks(autoRunnable);
                auto.setText("真实采样自动 0→140→0 测试");
            }
        });

        TextView styleLabel = label("声音风格");
        root.addView(styleLabel, matchWrap());

        RadioGroup styleGroup = new RadioGroup(this);
        styleGroup.setOrientation(RadioGroup.VERTICAL);

        sampleButton = addStyleRadio(styleGroup, ID_SAMPLE, "真实采样 VVVF 0→140km/h / 你提供的录音");
        siemensButton = addStyleRadio(styleGroup, ID_SIEMENS, "广东地铁西门子 GTO / 广州 1 号线 A1 味");
        gtoButton = addStyleRadio(styleGroup, ID_GTO, "通用 GTO 粗糙老电车");
        igbtButton = addStyleRadio(styleGroup, ID_IGBT, "通用 IGBT 顺滑现代电车");
        aircraftButton = addStyleRadio(styleGroup, ID_AIRCRAFT, "飞机 / 涡扇引擎推进感");
        popBangButton = addStyleRadio(styleGroup, ID_POP_BANG, "改偏时点火 / 涡轮回火放炮");
        naButton = addStyleRadio(styleGroup, ID_NA, "自然吸气 / 高转进气声");
        rotaryButton = addStyleRadio(styleGroup, ID_ROTARY, "转子发动机 / 高转蜂鸣");
        superchargedV8Button = addStyleRadio(styleGroup, ID_SUPERCHARGED_V8, "地狱猫风格 / 机械增压 V8");

        sampleButton.setChecked(true);
        root.addView(styleGroup, matchWrap());

        TextView stageHint = new TextView(this);
        stageHint.setText("V6：VVVF 默认改为真实采样映射。你提供的 WAV 是 0→140km/h，App 会把车速映射到录音位置，并用短粒度循环保持匀速。发动机类继续保留 V5 的曲轴/点火/排气脉冲模型，下一步会按 engine-sim 的节点/脚本思路继续重写。");
        stageHint.setTextSize(13);
        stageHint.setTextColor(Color.DKGRAY);
        stageHint.setPadding(0, dp(2), 0, dp(6));
        root.addView(stageHint, matchWrap());

        styleGroup.setOnCheckedChangeListener((group, checkedId) -> sendStyle(styleFromId(checkedId)));

        TextView volLabel = label("音量");
        root.addView(volLabel, matchWrap());
        volumeSeek = new SeekBar(this);
        volumeSeek.setMax(100);
        volumeSeek.setProgress(55);
        root.addView(volumeSeek, matchWrap());
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) sendVolume(progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        muteSwitch = new Switch(this);
        muteSwitch.setText("静音");
        muteSwitch.setTextSize(16);
        root.addView(muteSwitch, matchWrap());
        muteSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sendMute(isChecked);
            }
        });

        infoText = new TextView(this);
        infoText.setTextSize(13);
        infoText.setTextColor(Color.DKGRAY);
        infoText.setPadding(0, dp(14), 0, 0);
        infoText.setSingleLine(false);
        root.addView(infoText, matchWrap());

        return scroll;
    }

    private RadioButton addStyleRadio(RadioGroup group, int id, String text) {
        RadioButton b = new RadioButton(this);
        b.setId(id);
        b.setText(text);
        group.addView(b, matchWrap());
        return b;
    }

    private String styleFromId(int checkedId) {
        if (checkedId == ID_SAMPLE) return "SAMPLE_VVVF_0_140";
        if (checkedId == ID_SIEMENS) return "SIEMENS_GZ_GTO";
        if (checkedId == ID_IGBT) return "IGBT";
        if (checkedId == ID_AIRCRAFT) return "AIRCRAFT_TURBINE";
        if (checkedId == ID_POP_BANG) return "POP_BANG_TURBO";
        if (checkedId == ID_NA) return "NATURAL_ASPIRATED";
        if (checkedId == ID_ROTARY) return "ROTARY";
        if (checkedId == ID_SUPERCHARGED_V8) return "SUPERCHARGED_V8";
        return "GTO";
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(16);
        v.setTextColor(Color.rgb(40, 40, 40));
        v.setPadding(0, dp(18), 0, dp(4));
        return v;
    }

    private void startSoundService() {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        sendVolume(volumeSeek == null ? 0.55f : volumeSeek.getProgress() / 100f);
        if (sampleButton == null || sampleButton.isChecked()) sendStyle("SAMPLE_VVVF_0_140");
        else if (siemensButton != null && siemensButton.isChecked()) sendStyle("SIEMENS_GZ_GTO");
        else if (gtoButton != null && gtoButton.isChecked()) sendStyle("GTO");
        else if (igbtButton != null && igbtButton.isChecked()) sendStyle("IGBT");
        else if (aircraftButton != null && aircraftButton.isChecked()) sendStyle("AIRCRAFT_TURBINE");
        else if (popBangButton != null && popBangButton.isChecked()) sendStyle("POP_BANG_TURBO");
        else if (naButton != null && naButton.isChecked()) sendStyle("NATURAL_ASPIRATED");
        else if (rotaryButton != null && rotaryButton.isChecked()) sendStyle("ROTARY");
        else if (superchargedV8Button != null && superchargedV8Button.isChecked()) sendStyle("SUPERCHARGED_V8");
    }

    private void sendSpeed(float speed) {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_SPEED);
        i.putExtra(VvvfSoundService.EXTRA_SPEED, speed);
        startServiceCompat(i);
    }

    private void sendStyle(String style) {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_STYLE);
        i.putExtra(VvvfSoundService.EXTRA_STYLE, style);
        startServiceCompat(i);
    }

    private void sendVolume(float volume) {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_VOLUME);
        i.putExtra(VvvfSoundService.EXTRA_VOLUME, volume);
        startServiceCompat(i);
    }

    private void sendMute(boolean mute) {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_MUTE);
        i.putExtra(VvvfSoundService.EXTRA_MUTE, mute);
        startServiceCompat(i);
    }

    private void startServiceCompat(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void updateInfoText() {
        String ip = getLocalIpv4();
        String host = TextUtils.isEmpty(ip) ? "车机IP" : ip;
        String text = "局域网 UDP 指令：\n"
                + "  echo SPEED 45 | nc -u " + host + " 47230\n"
                + "  echo STYLE SAMPLE_VVVF_0_140 | nc -u " + host + " 47230\n"
                + "  echo STYLE SIEMENS_GZ_GTO | nc -u " + host + " 47230\n"
                + "  echo STYLE AIRCRAFT_TURBINE | nc -u " + host + " 47230\n"
                + "  echo STYLE POP_BANG_TURBO | nc -u " + host + " 47230\n"
                + "  echo STYLE NATURAL_ASPIRATED | nc -u " + host + " 47230\n"
                + "  echo STYLE ROTARY | nc -u " + host + " 47230\n"
                + "  echo STYLE SUPERCHARGED_V8 | nc -u " + host + " 47230\n\n"
                + "ADB 调试：\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 45\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SAMPLE_VVVF_0_140\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style POP_BANG_TURBO\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SUPERCHARGED_V8\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.STOP\n\n"
                + "后续接入 MikuCarLauncher 时，可只发 SPEED 当前车速；如果能发 STATE 车速 转速 油门开度，发动机模式会更真实。";
        infoText.setText(text);
    }

    private String getLocalIpv4() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (java.net.InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) return addr.getHostAddress();
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 39);
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightWrap(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
