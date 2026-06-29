package com.jlxc.mikuvvvf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class SpeedCommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Intent service = new Intent(context, VvvfSoundService.class);

        if ("com.jlxc.mikuvvvf.SET_SPEED".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_SET_SPEED);
            service.putExtra(VvvfSoundService.EXTRA_SPEED, intent.getFloatExtra("speed", 0f));
        } else if ("com.jlxc.mikuvvvf.SET_STYLE".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_SET_STYLE);
            service.putExtra(VvvfSoundService.EXTRA_STYLE, intent.getStringExtra("style"));
        } else if ("com.jlxc.mikuvvvf.SET_HOOK".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_SET_HOOK);
            service.putExtra(VvvfSoundService.EXTRA_HOOK_ENABLED, intent.getBooleanExtra("enabled", true));
        } else if ("com.jlxc.mikuvvvf.SET_BACKGROUND_MUTE".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_SET_BACKGROUND_MUTE);
            service.putExtra(VvvfSoundService.EXTRA_BACKGROUND_MUTE, intent.getBooleanExtra("enabled", true));
        } else if ("com.jlxc.mikuvvvf.SET_RPM_BIND".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_SET_RPM_BINDING);
            service.putExtra(VvvfSoundService.EXTRA_RPM_BINDING, intent.getBooleanExtra("enabled", true));
        } else if ("com.jlxc.mikuvvvf.SET_CUSTOM_VVVF".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_SET_CUSTOM_VVVF);
            service.putExtra(VvvfSoundService.EXTRA_CUSTOM_CUT1, (double) intent.getFloatExtra("cut1", 34.5f));
            service.putExtra(VvvfSoundService.EXTRA_CUSTOM_CUT2, (double) intent.getFloatExtra("cut2", 38.0f));
            service.putExtra(VvvfSoundService.EXTRA_CUSTOM_MAX_SPEED, (double) intent.getFloatExtra("max", 160.0f));
            service.putExtra(VvvfSoundService.EXTRA_CUSTOM_ASYNC_HZ, (double) intent.getFloatExtra("async", 1050.0f));
            service.putExtra(VvvfSoundService.EXTRA_CUSTOM_SYNC_PULSES, intent.getIntExtra("sync", 9));
            service.putExtra(VvvfSoundService.EXTRA_CUSTOM_WIDE_PULSES, intent.getIntExtra("wide", 3));
        } else if ("com.jlxc.mikuvvvf.STOP".equals(action)) {
            service.setAction(VvvfSoundService.ACTION_STOP);
        } else {
            service.setAction(VvvfSoundService.ACTION_START);
        }

        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }
}
