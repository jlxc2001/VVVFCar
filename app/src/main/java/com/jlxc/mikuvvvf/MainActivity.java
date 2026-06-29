package com.jlxc.mikuvvvf;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
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
    private static final int ID_CUSTOM = 1009;

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
    private HudView hudView;
    private TextView speedText;
    private TextView unitText;
    private TextView settingsInfoText;
    private SeekBar speedSeek;
    private SeekBar volumeSeek;
    private Switch muteSwitch;
    private Switch hookSwitch;
    private Switch backgroundMuteSwitch;
    private Switch rpmBindSwitch;
    private RadioButton sampleButton;
    private RadioButton customButton;
    private RadioButton gtoButton;
    private RadioButton igbtButton;
    private RadioButton siemensButton;
    private RadioButton aircraftButton;
    private RadioButton popBangButton;
    private RadioButton naButton;
    private RadioButton rotaryButton;
    private RadioButton superchargedV8Button;
    private Button autoButton;
    private Dialog settingsDialog;
    private TextView customSummaryText;
    private TextView customCut1Label;
    private TextView customCut2Label;
    private TextView customMaxLabel;
    private TextView customAsyncLabel;
    private TextView customSyncLabel;
    private TextView customWideLabel;
    private SeekBar customCut1Seek;
    private SeekBar customCut2Seek;
    private SeekBar customMaxSeek;
    private SeekBar customAsyncSeek;
    private SeekBar customSyncSeek;
    private SeekBar customWideSeek;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean autoTest = false;
    private float autoSpeed = 0f;
    private int autoDirection = 1;
    private float displaySpeed = 0f;
    private String currentStyle = "SAMPLE_VVVF_0_140";
    private String currentSource = "HOOK";
    private boolean currentBackgroundMute = true;
    private boolean currentRpmBinding = false;
    private boolean currentEffectiveMute = false;
    private String currentCustomSummary = "0-34.5 Async 1050Hz / 34.5-38.0 Sync 9P / 38.0-160.0 Wide 3P";

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !VvvfSoundService.ACTION_STATUS.equals(intent.getAction())) return;
            float speed = intent.getFloatExtra(VvvfSoundService.EXTRA_STATUS_SPEED, displaySpeed);
            displaySpeed = speed;
            currentStyle = intent.getStringExtra(VvvfSoundService.EXTRA_STATUS_STYLE);
            currentSource = intent.getStringExtra(VvvfSoundService.EXTRA_STATUS_SOURCE);
            currentBackgroundMute = intent.getBooleanExtra(VvvfSoundService.EXTRA_STATUS_BACKGROUND_MUTE, currentBackgroundMute);
            currentRpmBinding = intent.getBooleanExtra(VvvfSoundService.EXTRA_STATUS_RPM_BINDING, currentRpmBinding);
            currentEffectiveMute = intent.getBooleanExtra(VvvfSoundService.EXTRA_STATUS_EFFECTIVE_MUTE, currentEffectiveMute);
            String cs = intent.getStringExtra(VvvfSoundService.EXTRA_STATUS_CUSTOM_SUMMARY);
            if (!TextUtils.isEmpty(cs)) currentCustomSummary = cs;
            updateHudSpeed(speed);
            updateInfoText();
        }
    };

    private final Runnable autoRunnable = new Runnable() {
        @Override public void run() {
            if (!autoTest) return;
            autoSpeed += autoDirection * 0.10f;
            if (autoSpeed >= 140f) { autoSpeed = 140f; autoDirection = -1; }
            if (autoSpeed <= 0f) { autoSpeed = 0f; autoDirection = 1; }
            if (speedSeek != null) speedSeek.setProgress(Math.round(autoSpeed * 10f));
            sendSpeed(autoSpeed);
            handler.postDelayed(this, 90);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        currentStyle = prefs.getString(KEY_STYLE, "SAMPLE_VVVF_0_140");
        currentBackgroundMute = prefs.getBoolean(KEY_BACKGROUND_MUTE, true);
        currentRpmBinding = prefs.getBoolean(KEY_RPM_BINDING, false);
        maybeRequestNotificationPermission();
        setContentView(buildHudView());
        startSoundService();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(VvvfSoundService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
        sendAppForeground(true);
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(statusReceiver); } catch (Throwable ignored) {}
        sendAppForeground(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        autoTest = false;
        handler.removeCallbacks(autoRunnable);
        if (settingsDialog != null) {
            try { settingsDialog.dismiss(); } catch (Throwable ignored) {}
            settingsDialog = null;
        }
        super.onDestroy();
    }

    private View buildHudView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setKeepScreenOn(true);
        root.setOnLongClickListener(v -> {
            showSettingsDialog();
            return true;
        });

        hudView = new HudView(this);
        root.addView(hudView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout speedBox = new LinearLayout(this);
        speedBox.setOrientation(LinearLayout.VERTICAL);
        speedBox.setGravity(Gravity.CENTER);
        speedBox.setPadding(dp(12), dp(12), dp(12), dp(12));

        speedText = new TextView(this);
        speedText.setText("0.0");
        speedText.setTextSize(116);
        speedText.setGravity(Gravity.CENTER);
        speedText.setIncludeFontPadding(false);
        speedText.setTextColor(Color.rgb(60, 255, 245));
        speedText.setShadowLayer(dp(12), 0, 0, Color.rgb(0, 210, 255));
        speedBox.addView(speedText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        unitText = new TextView(this);
        unitText.setText("km/h");
        unitText.setTextSize(26);
        unitText.setGravity(Gravity.CENTER);
        unitText.setTextColor(Color.rgb(120, 245, 255));
        unitText.setLetterSpacing(0.18f);
        unitText.setShadowLayer(dp(7), 0, 0, Color.rgb(0, 160, 255));
        speedBox.addView(unitText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(speedBox, boxLp);

        return root;
    }

    private void updateHudSpeed(float speed) {
        if (Float.isNaN(speed) || Float.isInfinite(speed)) speed = 0f;
        speed = Math.max(0f, Math.min(260f, speed));
        if (speedText != null) speedText.setText(String.format(Locale.US, "%.1f", speed));
        if (hudView != null) hudView.setSpeed(speed);
    }

    private void showSettingsDialog() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;

        settingsDialog = new Dialog(this);
        settingsDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        settingsDialog.setContentView(buildSettingsContent());
        Window window = settingsDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(64), dp(760));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
        }
        settingsDialog.show();
        updateInfoText();
    }

    private View buildSettingsContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));
        root.setBackgroundColor(Color.rgb(3, 13, 18));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Miku VVVF 战斗 HUD 设置");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(80, 255, 245));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setShadowLayer(dp(7), 0, 0, Color.rgb(0, 160, 255));
        root.addView(title, matchWrap());

        TextView sub = new TextView(this);
        sub.setText("调试项已隐藏到这里 · 长按主界面打开 · 默认主界面只显示车速");
        sub.setTextSize(12);
        sub.setTextColor(Color.rgb(130, 210, 215));
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(6), 0, dp(12));
        root.addView(sub, matchWrap());

        LinearLayout buttons1 = new LinearLayout(this);
        buttons1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons1, matchWrap());

        Button start = tacticalButton("启动服务");
        buttons1.addView(start, weightWrap(1));
        start.setOnClickListener(v -> startSoundService());

        Button stop = tacticalButton("停止服务");
        buttons1.addView(stop, weightWrap(1));
        stop.setOnClickListener(v -> {
            autoTest = false;
            updateAutoButtonText();
            Intent i = new Intent(this, VvvfSoundService.class);
            i.setAction(VvvfSoundService.ACTION_STOP);
            startServiceCompat(i);
        });

        Button zero = tacticalButton("归零");
        buttons1.addView(zero, weightWrap(1));
        zero.setOnClickListener(v -> {
            autoTest = false;
            updateAutoButtonText();
            if (speedSeek != null) speedSeek.setProgress(0);
            sendSpeed(0f);
        });

        hookSwitch = new Switch(this);
        hookSwitch.setText("使用 MainApp Hook 车速数据源（默认开启，最低 500ms 轮询）");
        hookSwitch.setTextSize(14);
        hookSwitch.setTextColor(Color.rgb(210, 250, 250));
        hookSwitch.setChecked(prefs == null || prefs.getBoolean(KEY_HOOK, true));
        hookSwitch.setPadding(0, dp(12), 0, dp(4));
        root.addView(hookSwitch, matchWrap());
        hookSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> sendHookEnabled(isChecked));

        TextView hookHint = smallHint("Hook 开启时，实车速度来自 com.ts.MainUI 的 CarInfoService；手动滑条/UDP SPEED 只作为离车调试。音频线程内部持续插值，避免 500ms 数据源导致声音一卡一卡。");
        root.addView(hookHint, matchWrap());

        backgroundMuteSwitch = new Switch(this);
        backgroundMuteSwitch.setText("软件不在前台时关闭声音（默认开启）");
        backgroundMuteSwitch.setTextSize(14);
        backgroundMuteSwitch.setTextColor(Color.rgb(210, 250, 250));
        backgroundMuteSwitch.setChecked(prefs == null || prefs.getBoolean(KEY_BACKGROUND_MUTE, true));
        root.addView(backgroundMuteSwitch, matchWrap());
        backgroundMuteSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> sendBackgroundMute(isChecked));

        rpmBindSwitch = new Switch(this);
        rpmBindSwitch.setText("根据发动机转速绑定声音，而不是车速");
        rpmBindSwitch.setTextSize(14);
        rpmBindSwitch.setTextColor(Color.rgb(210, 250, 250));
        rpmBindSwitch.setChecked(prefs != null && prefs.getBoolean(KEY_RPM_BINDING, false));
        root.addView(rpmBindSwitch, matchWrap());
        rpmBindSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> sendRpmBinding(isChecked));

        TextView speedLabel = label("离车调试速度");
        root.addView(speedLabel, matchWrap());
        speedSeek = new SeekBar(this);
        speedSeek.setMax(2400);
        speedSeek.setProgress(Math.round(displaySpeed * 10f));
        speedSeek.setPadding(0, dp(6), 0, dp(4));
        root.addView(speedSeek, matchWrap());
        speedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float speed = progress / 10f;
                if (fromUser) {
                    autoTest = false;
                    updateAutoButtonText();
                    sendSpeed(speed);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        autoButton = tacticalButton("自动 0→140→0 测试（会临时关闭 Hook）");
        root.addView(autoButton, matchWrap());
        autoButton.setOnClickListener(v -> {
            autoTest = !autoTest;
            if (autoTest) {
                if (hookSwitch != null) hookSwitch.setChecked(false);
                sendHookEnabled(false);
                autoSpeed = speedSeek == null ? displaySpeed : speedSeek.getProgress() / 10f;
                autoDirection = autoSpeed >= 140f ? -1 : 1;
                handler.removeCallbacks(autoRunnable);
                handler.post(autoRunnable);
            } else {
                handler.removeCallbacks(autoRunnable);
            }
            updateAutoButtonText();
        });

        TextView styleLabel = label("声音风格");
        root.addView(styleLabel, matchWrap());

        RadioGroup styleGroup = new RadioGroup(this);
        styleGroup.setOrientation(RadioGroup.VERTICAL);
        styleGroup.setPadding(0, 0, 0, dp(2));

        sampleButton = addStyleRadio(styleGroup, ID_SAMPLE, "真实采样 VVVF 0→140km/h / 你提供的录音");
        customButton = addStyleRadio(styleGroup, ID_CUSTOM, "自定义 VVVF / 按截图：0-34.5 Async 1050Hz，34.5-38 Sync 9P，38-160 Wide 3P");
        siemensButton = addStyleRadio(styleGroup, ID_SIEMENS, "广东地铁西门子 GTO / 广州 1 号线 A1 味");
        gtoButton = addStyleRadio(styleGroup, ID_GTO, "通用 GTO 粗糙老电车");
        igbtButton = addStyleRadio(styleGroup, ID_IGBT, "通用 IGBT 顺滑现代电车");
        aircraftButton = addStyleRadio(styleGroup, ID_AIRCRAFT, "飞机 / 涡扇引擎推进感");
        popBangButton = addStyleRadio(styleGroup, ID_POP_BANG, "改偏时点火 / 涡轮回火放炮");
        naButton = addStyleRadio(styleGroup, ID_NA, "自然吸气 / 高转进气声");
        rotaryButton = addStyleRadio(styleGroup, ID_ROTARY, "转子发动机 / 高转蜂鸣");
        superchargedV8Button = addStyleRadio(styleGroup, ID_SUPERCHARGED_V8, "地狱猫风格 / 机械增压 V8");
        setCheckedStyleButton(currentStyle == null ? "SAMPLE_VVVF_0_140" : currentStyle);
        root.addView(styleGroup, matchWrap());
        styleGroup.setOnCheckedChangeListener((group, checkedId) -> sendStyle(styleFromId(checkedId)));

        addCustomVvvfControls(root);

        TextView volLabel = label("音量");
        root.addView(volLabel, matchWrap());
        volumeSeek = new SeekBar(this);
        volumeSeek.setMax(100);
        volumeSeek.setProgress(Math.round((prefs == null ? 0.55f : prefs.getFloat(KEY_VOLUME, 0.55f)) * 100f));
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
        muteSwitch.setTextSize(15);
        muteSwitch.setTextColor(Color.rgb(210, 250, 250));
        muteSwitch.setChecked(prefs != null && prefs.getBoolean(KEY_MUTED, false));
        root.addView(muteSwitch, matchWrap());
        muteSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sendMute(isChecked);
            }
        });

        settingsInfoText = new TextView(this);
        settingsInfoText.setTextSize(12);
        settingsInfoText.setTextColor(Color.rgb(120, 200, 205));
        settingsInfoText.setPadding(0, dp(12), 0, dp(10));
        settingsInfoText.setSingleLine(false);
        root.addView(settingsInfoText, matchWrap());
        updateInfoText();

        Button close = tacticalButton("关闭设置");
        root.addView(close, matchWrap());
        close.setOnClickListener(v -> {
            if (settingsDialog != null) settingsDialog.dismiss();
        });

        return scroll;
    }

    private void addCustomVvvfControls(LinearLayout root) {
        TextView customLabel = label("自定义 VVVF 参数");
        root.addView(customLabel, matchWrap());

        customSummaryText = smallHint("当前自定义：" + currentCustomSummary);
        root.addView(customSummaryText, matchWrap());

        customCut1Label = smallHint("");
        root.addView(customCut1Label, matchWrap());
        customCut1Seek = new SeekBar(this);
        customCut1Seek.setMax(1200);
        customCut1Seek.setProgress(Math.round((prefs == null ? 34.5f : prefs.getFloat(KEY_CUSTOM_CUT1, 34.5f)) * 10f));
        root.addView(customCut1Seek, matchWrap());

        customCut2Label = smallHint("");
        root.addView(customCut2Label, matchWrap());
        customCut2Seek = new SeekBar(this);
        customCut2Seek.setMax(1800);
        customCut2Seek.setProgress(Math.round((prefs == null ? 38.0f : prefs.getFloat(KEY_CUSTOM_CUT2, 38.0f)) * 10f));
        root.addView(customCut2Seek, matchWrap());

        customMaxLabel = smallHint("");
        root.addView(customMaxLabel, matchWrap());
        customMaxSeek = new SeekBar(this);
        customMaxSeek.setMax(2600);
        customMaxSeek.setProgress(Math.round((prefs == null ? 160.0f : prefs.getFloat(KEY_CUSTOM_MAX, 160.0f)) * 10f));
        root.addView(customMaxSeek, matchWrap());

        customAsyncLabel = smallHint("");
        root.addView(customAsyncLabel, matchWrap());
        customAsyncSeek = new SeekBar(this);
        customAsyncSeek.setMax(6000);
        customAsyncSeek.setProgress(Math.round(prefs == null ? 1050f : prefs.getFloat(KEY_CUSTOM_ASYNC, 1050f)));
        root.addView(customAsyncSeek, matchWrap());

        customSyncLabel = smallHint("");
        root.addView(customSyncLabel, matchWrap());
        customSyncSeek = new SeekBar(this);
        customSyncSeek.setMax(31);
        customSyncSeek.setProgress(prefs == null ? 9 : prefs.getInt(KEY_CUSTOM_SYNC, 9));
        root.addView(customSyncSeek, matchWrap());

        customWideLabel = smallHint("");
        root.addView(customWideLabel, matchWrap());
        customWideSeek = new SeekBar(this);
        customWideSeek.setMax(31);
        customWideSeek.setProgress(prefs == null ? 3 : prefs.getInt(KEY_CUSTOM_WIDE, 3));
        root.addView(customWideSeek, matchWrap());

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateCustomLabels();
                if (fromUser) sendCustomVvvf();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                updateCustomLabels();
                sendCustomVvvf();
            }
        };
        customCut1Seek.setOnSeekBarChangeListener(listener);
        customCut2Seek.setOnSeekBarChangeListener(listener);
        customMaxSeek.setOnSeekBarChangeListener(listener);
        customAsyncSeek.setOnSeekBarChangeListener(listener);
        customSyncSeek.setOnSeekBarChangeListener(listener);
        customWideSeek.setOnSeekBarChangeListener(listener);
        updateCustomLabels();
    }

    private void updateCustomLabels() {
        if (customCut1Seek == null) return;
        double cut1 = customCut1Seek.getProgress() / 10.0;
        double cut2 = customCut2Seek.getProgress() / 10.0;
        double max = customMaxSeek.getProgress() / 10.0;
        double async = Math.max(120, customAsyncSeek.getProgress());
        int sync = Math.max(1, customSyncSeek.getProgress());
        int wide = Math.max(1, customWideSeek.getProgress());
        if (customCut1Label != null) customCut1Label.setText(String.format(Locale.US, "A段结束速度：%.1f km/h", cut1));
        if (customCut2Label != null) customCut2Label.setText(String.format(Locale.US, "9脉冲→宽3脉冲切换速度：%.1f km/h", cut2));
        if (customMaxLabel != null) customMaxLabel.setText(String.format(Locale.US, "自定义映射最高速度：%.1f km/h", max));
        if (customAsyncLabel != null) customAsyncLabel.setText(String.format(Locale.US, "Asynchronous 固定载波：%.0f Hz", async));
        if (customSyncLabel != null) customSyncLabel.setText("Synchronous 脉冲数：" + sync + " Pulses");
        if (customWideLabel != null) customWideLabel.setText("Wide 同步脉冲数：" + wide + " Pulses");
        if (customSummaryText != null) {
            customSummaryText.setText(String.format(Locale.US,
                    "当前自定义：0-%.1f km/h Async %.0fHz / %.1f-%.1f km/h Sync %dP / %.1f-%.1f km/h Wide %dP",
                    cut1, async, cut1, cut2, sync, cut2, max, wide));
        }
    }

    private void updateAutoButtonText() {
        if (autoButton != null) autoButton.setText(autoTest ? "停止自动测试" : "自动 0→140→0 测试（会临时关闭 Hook）");
    }

    private Button tacticalButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.rgb(15, 245, 235));
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.rgb(5, 35, 43));
        return b;
    }

    private RadioButton addStyleRadio(RadioGroup group, int id, String text) {
        RadioButton b = new RadioButton(this);
        b.setId(id);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(Color.rgb(215, 250, 250));
        group.addView(b, matchWrap());
        return b;
    }

    private void setCheckedStyleButton(String style) {
        String s = style == null ? "" : style.toUpperCase(Locale.US);
        if (s.contains("CUSTOM")) customButton.setChecked(true);
        else if (s.contains("SIEMENS")) siemensButton.setChecked(true);
        else if (s.contains("IGBT")) igbtButton.setChecked(true);
        else if (s.contains("AIR")) aircraftButton.setChecked(true);
        else if (s.contains("POP")) popBangButton.setChecked(true);
        else if (s.contains("NATURAL")) naButton.setChecked(true);
        else if (s.contains("ROTARY")) rotaryButton.setChecked(true);
        else if (s.contains("SUPER")) superchargedV8Button.setChecked(true);
        else if (s.equals("GTO")) gtoButton.setChecked(true);
        else sampleButton.setChecked(true);
    }

    private String styleFromId(int checkedId) {
        if (checkedId == ID_SAMPLE) return "SAMPLE_VVVF_0_140";
        if (checkedId == ID_CUSTOM) return "CUSTOM_VVVF";
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
        v.setTextSize(15);
        v.setTextColor(Color.rgb(85, 255, 245));
        v.setPadding(0, dp(16), 0, dp(4));
        v.setLetterSpacing(0.08f);
        return v;
    }

    private TextView smallHint(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12);
        v.setTextColor(Color.rgb(120, 200, 205));
        v.setPadding(0, dp(2), 0, dp(8));
        return v;
    }

    private void startSoundService() {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_START);
        startServiceCompat(i);
        if (volumeSeek != null) sendVolume(volumeSeek.getProgress() / 100f);
        if (hookSwitch != null) sendHookEnabled(hookSwitch.isChecked());
        if (backgroundMuteSwitch != null) sendBackgroundMute(backgroundMuteSwitch.isChecked());
        if (rpmBindSwitch != null) sendRpmBinding(rpmBindSwitch.isChecked());
    }

    private void sendSpeed(float speed) {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_SPEED);
        i.putExtra(VvvfSoundService.EXTRA_SPEED, speed);
        startServiceCompat(i);
    }

    private void sendStyle(String style) {
        currentStyle = style;
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

    private void sendHookEnabled(boolean enabled) {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_HOOK);
        i.putExtra(VvvfSoundService.EXTRA_HOOK_ENABLED, enabled);
        startServiceCompat(i);
    }

    private void sendAppForeground(boolean foreground) {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_FOREGROUND);
        i.putExtra(VvvfSoundService.EXTRA_FOREGROUND, foreground);
        startServiceCompat(i);
    }

    private void sendBackgroundMute(boolean enabled) {
        currentBackgroundMute = enabled;
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_BACKGROUND_MUTE);
        i.putExtra(VvvfSoundService.EXTRA_BACKGROUND_MUTE, enabled);
        startServiceCompat(i);
    }

    private void sendRpmBinding(boolean enabled) {
        currentRpmBinding = enabled;
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_RPM_BINDING);
        i.putExtra(VvvfSoundService.EXTRA_RPM_BINDING, enabled);
        startServiceCompat(i);
    }

    private void sendCustomVvvf() {
        if (customCut1Seek == null) return;
        double cut1 = customCut1Seek.getProgress() / 10.0;
        double cut2 = customCut2Seek.getProgress() / 10.0;
        double max = customMaxSeek.getProgress() / 10.0;
        double async = customAsyncSeek.getProgress();
        int sync = Math.max(1, customSyncSeek.getProgress());
        int wide = Math.max(1, customWideSeek.getProgress());
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_CUSTOM_VVVF);
        i.putExtra(VvvfSoundService.EXTRA_CUSTOM_CUT1, cut1);
        i.putExtra(VvvfSoundService.EXTRA_CUSTOM_CUT2, cut2);
        i.putExtra(VvvfSoundService.EXTRA_CUSTOM_MAX_SPEED, max);
        i.putExtra(VvvfSoundService.EXTRA_CUSTOM_ASYNC_HZ, async);
        i.putExtra(VvvfSoundService.EXTRA_CUSTOM_SYNC_PULSES, sync);
        i.putExtra(VvvfSoundService.EXTRA_CUSTOM_WIDE_PULSES, wide);
        startServiceCompat(i);
    }

    private void startServiceCompat(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void updateInfoText() {
        if (settingsInfoText == null || settingsDialog == null || !settingsDialog.isShowing()) return;
        String ip = getLocalIpv4();
        String host = TextUtils.isEmpty(ip) ? "车机IP" : ip;
        String text = "当前：" + (currentStyle == null ? "SAMPLE_VVVF_0_140" : currentStyle)
                + " · " + (TextUtils.isEmpty(currentSource) ? "HOOK" : currentSource)
                + " · RPM绑定=" + (currentRpmBinding ? "ON" : "OFF")
                + " · 后台静音=" + (currentBackgroundMute ? "ON" : "OFF")
                + (currentEffectiveMute ? " · 当前静音" : "") + "\n"
                + "自定义VVVF：" + currentCustomSummary + "\n\n"
                + "局域网 UDP 指令：\n"
                + "  echo SPEED 45 | nc -u " + host + " 47230\n"
                + "  echo STYLE SAMPLE_VVVF_0_140 | nc -u " + host + " 47230\n"
                + "  echo STYLE AIRCRAFT_TURBINE | nc -u " + host + " 47230\n"
                + "  echo STYLE CUSTOM_VVVF | nc -u " + host + " 47230\n"
                + "  echo CUSTOM 34.5 38 160 1050 9 3 | nc -u " + host + " 47230\n"
                + "  echo RPMBIND 1 | nc -u " + host + " 47230\n"
                + "  echo BGMUTE 1 | nc -u " + host + " 47230\n"
                + "  echo HOOK 0 | nc -u " + host + " 47230\n"
                + "  echo HOOK 1 | nc -u " + host + " 47230\n"
                + "  echo POLL 500 | nc -u " + host + " 47230\n\n"
                + "ADB 调试：\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 45\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SAMPLE_VVVF_0_140\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style AIRCRAFT_TURBINE\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style CUSTOM_VVVF\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_RPM_BIND --ez enabled true\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_BACKGROUND_MUTE --ez enabled true\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_HOOK --ez enabled false\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_HOOK --ez enabled true\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.STOP\n\n"
                + "主界面无按钮。设置入口：长按屏幕。";
        settingsInfoText.setText(text);
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

    private static class HudView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float speed = 0f;

        HudView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.SQUARE);
            thin.setStyle(Paint.Style.STROKE);
            thin.setStrokeCap(Paint.Cap.SQUARE);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeCap(Paint.Cap.ROUND);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setSpeed(float value) {
            speed = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth();
            int h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float base = Math.min(w, h);

            c.drawColor(Color.BLACK);

            glow.setColor(Color.argb(55, 0, 210, 255));
            glow.setStrokeWidth(base * 0.010f);
            c.drawCircle(cx, cy, base * 0.39f, glow);
            c.drawCircle(cx, cy, base * 0.26f, glow);

            thin.setColor(Color.argb(120, 35, 255, 245));
            thin.setStrokeWidth(Math.max(1f, base * 0.0020f));
            for (int i = 0; i < 13; i++) {
                float y = h * (0.10f + i * 0.067f);
                c.drawLine(w * 0.07f, y, w * 0.18f, y, thin);
                c.drawLine(w * 0.82f, y, w * 0.93f, y, thin);
            }

            paint.setColor(Color.rgb(60, 255, 245));
            paint.setStrokeWidth(Math.max(2f, base * 0.0035f));

            float bracketW = w * 0.18f;
            float bracketH = h * 0.18f;
            drawCorner(c, w * 0.08f, h * 0.12f, bracketW, bracketH, true, true);
            drawCorner(c, w * 0.92f, h * 0.12f, bracketW, bracketH, false, true);
            drawCorner(c, w * 0.08f, h * 0.88f, bracketW, bracketH, true, false);
            drawCorner(c, w * 0.92f, h * 0.88f, bracketW, bracketH, false, false);

            path.reset();
            path.moveTo(cx - base * 0.43f, cy);
            path.lineTo(cx - base * 0.30f, cy - base * 0.07f);
            path.lineTo(cx - base * 0.15f, cy - base * 0.07f);
            path.moveTo(cx + base * 0.43f, cy);
            path.lineTo(cx + base * 0.30f, cy - base * 0.07f);
            path.lineTo(cx + base * 0.15f, cy - base * 0.07f);
            path.moveTo(cx - base * 0.43f, cy + base * 0.02f);
            path.lineTo(cx - base * 0.30f, cy + base * 0.09f);
            path.lineTo(cx - base * 0.15f, cy + base * 0.09f);
            path.moveTo(cx + base * 0.43f, cy + base * 0.02f);
            path.lineTo(cx + base * 0.30f, cy + base * 0.09f);
            path.lineTo(cx + base * 0.15f, cy + base * 0.09f);
            c.drawPath(path, paint);

            thin.setColor(Color.argb(190, 80, 255, 245));
            thin.setStrokeWidth(Math.max(1f, base * 0.0025f));
            c.drawLine(cx - base * 0.055f, cy, cx + base * 0.055f, cy, thin);
            c.drawLine(cx, cy - base * 0.055f, cx, cy + base * 0.055f, thin);
            c.drawCircle(cx, cy, base * 0.075f, thin);

            drawSpeedArc(c, cx, cy, base);
            drawScanLines(c, w, h, base);
        }

        private void drawCorner(Canvas c, float x, float y, float bw, float bh, boolean left, boolean top) {
            float sx = left ? 1f : -1f;
            float sy = top ? 1f : -1f;
            c.drawLine(x, y, x + sx * bw, y, paint);
            c.drawLine(x, y, x, y + sy * bh, paint);
            c.drawLine(x + sx * bw * 0.72f, y + sy * bh * 0.14f, x + sx * bw, y + sy * bh * 0.14f, thin);
            c.drawLine(x + sx * bw * 0.14f, y + sy * bh * 0.72f, x + sx * bw * 0.14f, y + sy * bh, thin);
        }

        private void drawSpeedArc(Canvas c, float cx, float cy, float base) {
            float r = base * 0.43f;
            RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
            thin.setColor(Color.argb(80, 60, 255, 245));
            thin.setStrokeWidth(Math.max(1f, base * 0.0025f));
            c.drawArc(oval, 205, 130, false, thin);
            c.drawArc(oval, -25, 130, false, thin);

            paint.setStrokeWidth(Math.max(2f, base * 0.004f));
            paint.setColor(Color.rgb(60, 255, 245));
            float sweep = Math.max(0f, Math.min(1f, speed / 260f)) * 130f;
            c.drawArc(oval, 205, sweep, false, paint);

            for (int i = 0; i <= 14; i++) {
                double deg = Math.toRadians(205 + i * (130.0 / 14.0));
                float r1 = r - base * (i % 2 == 0 ? 0.038f : 0.024f);
                float r2 = r;
                float x1 = cx + (float) Math.cos(deg) * r1;
                float y1 = cy + (float) Math.sin(deg) * r1;
                float x2 = cx + (float) Math.cos(deg) * r2;
                float y2 = cy + (float) Math.sin(deg) * r2;
                c.drawLine(x1, y1, x2, y2, thin);
            }
        }

        private void drawScanLines(Canvas c, int w, int h, float base) {
            thin.setColor(Color.argb(38, 80, 255, 245));
            thin.setStrokeWidth(1f);
            float gap = Math.max(8f, base * 0.018f);
            for (float y = 0; y < h; y += gap) {
                c.drawLine(0, y, w, y, thin);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            return super.onTouchEvent(event);
        }
    }
}
