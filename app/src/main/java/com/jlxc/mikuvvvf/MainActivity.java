package com.jlxc.mikuvvvf;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
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
    private TextView speedText;
    private TextView infoText;
    private SeekBar speedSeek;
    private SeekBar volumeSeek;
    private Switch muteSwitch;
    private RadioButton gtoButton;
    private RadioButton igbtButton;
    private RadioButton siemensButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean autoTest = false;
    private float autoSpeed = 0f;
    private int autoDirection = 1;

    private final Runnable autoRunnable = new Runnable() {
        @Override public void run() {
            if (!autoTest) return;
            autoSpeed += autoDirection * 0.8f;
            if (autoSpeed >= 100f) { autoSpeed = 100f; autoDirection = -1; }
            if (autoSpeed <= 0f) { autoSpeed = 0f; autoDirection = 1; }
            speedSeek.setProgress(Math.round(autoSpeed * 10f));
            sendSpeed(autoSpeed);
            handler.postDelayed(this, 45);
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
        title.setText("Miku VVVF Sound Demo");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(0, 120, 130));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView sub = new TextView(this);
        sub.setText("车速绑定 VVVF 声浪模拟 V3 · 广东地铁西门子预设 · UDP 47230");
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
        speedSeek.setMax(1200); // 0.0 - 120.0 km/h
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
        auto.setText("自动 0→100→0 测试");
        root.addView(auto, matchWrap());
        auto.setOnClickListener(v -> {
            autoTest = !autoTest;
            if (autoTest) {
                autoSpeed = speedSeek.getProgress() / 10f;
                autoDirection = autoSpeed >= 100f ? -1 : 1;
                handler.removeCallbacks(autoRunnable);
                handler.post(autoRunnable);
                auto.setText("停止自动测试");
            } else {
                handler.removeCallbacks(autoRunnable);
                auto.setText("自动 0→100→0 测试");
            }
        });

        TextView styleLabel = label("声音风格");
        root.addView(styleLabel, matchWrap());

        RadioGroup styleGroup = new RadioGroup(this);
        styleGroup.setOrientation(RadioGroup.VERTICAL);
        siemensButton = new RadioButton(this);
        siemensButton.setText("广东地铁西门子 GTO / 广州 1 号线 A1 味");
        siemensButton.setId(1003);
        gtoButton = new RadioButton(this);
        gtoButton.setText("通用 GTO 粗糙老电车");
        gtoButton.setId(1001);
        igbtButton = new RadioButton(this);
        igbtButton.setText("通用 IGBT 顺滑现代电车");
        igbtButton.setId(1002);
        styleGroup.addView(siemensButton, matchWrap());
        styleGroup.addView(gtoButton, matchWrap());
        styleGroup.addView(igbtButton, matchWrap());
        siemensButton.setChecked(true);
        root.addView(styleGroup, matchWrap());

        TextView stageHint = new TextView(this);
        stageHint.setText("西门子换段：5.5 / 18 / 32 / 52 / 78 km/h；GTO：8 / 24 / 42 / 68；IGBT：16 / 36 / 78");
        stageHint.setTextSize(13);
        stageHint.setTextColor(Color.DKGRAY);
        stageHint.setPadding(0, dp(2), 0, dp(6));
        root.addView(stageHint, matchWrap());

        styleGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == 1003) sendStyle("SIEMENS_GZ_GTO");
            else if (checkedId == 1002) sendStyle("IGBT");
            else sendStyle("GTO");
        });

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
        if (siemensButton == null || siemensButton.isChecked()) sendStyle("SIEMENS_GZ_GTO");
        else if (gtoButton != null && gtoButton.isChecked()) sendStyle("GTO");
        else sendStyle("IGBT");
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
        String text = "局域网 UDP 指令：\n"
                + "  echo SPEED 45 | nc -u " + (TextUtils.isEmpty(ip) ? "车机IP" : ip) + " 47230\n"
                + "  echo STYLE SIEMENS_GZ_GTO | nc -u " + (TextUtils.isEmpty(ip) ? "车机IP" : ip) + " 47230\n"
                + "  echo STYLE IGBT | nc -u " + (TextUtils.isEmpty(ip) ? "车机IP" : ip) + " 47230\n\n"
                + "ADB 调试：\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 45\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SIEMENS_GZ_GTO\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style GTO\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.STOP\n\n"
                + "后续接入 MikuCarLauncher 时，只要持续发送 SPEED 当前车速 即可。";
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
