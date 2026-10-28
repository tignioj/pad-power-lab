package com.padpowerlab;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;

final class Telemetry {
    int level = -1, millivolts, microamps, plugged, status, thermal;
    float temperature = Float.NaN;
    double watts = Double.NaN;
    boolean currentSupported;
    int currentSign = 1;

    void read(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int raw = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            level = raw >= 0 && scale > 0 ? raw * 100 / scale : -1;
            millivolts = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            temperature = t == Integer.MIN_VALUE ? Float.NaN : t / 10f;
            plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            status = b.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        }
        BatteryManager manager = (BatteryManager)c.getSystemService(Context.BATTERY_SERVICE);
        microamps = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        currentSupported = microamps != Integer.MIN_VALUE && millivolts > 0;
        watts = currentSupported ? currentSign * millivolts * (double)microamps / 1_000_000_000.0 : Double.NaN;
        thermal = ((PowerManager)c.getSystemService(Context.POWER_SERVICE)).getCurrentThermalStatus();
    }

    String charging() {
        if (plugged == 0) return "未接电源";
        if (status == BatteryManager.BATTERY_STATUS_FULL) return "已接电源 · 电池已满";
        if (status == BatteryManager.BATTERY_STATUS_CHARGING) return "已接电源 · 正在充电";
        return "已接电源 · 电池未充电";
    }
    static String thermalName(int status) {
        String[] names = {"正常", "轻度限频", "中度限频", "严重限频", "临界过热", "紧急过热", "即将关机"};
        return status >= 0 && status < names.length ? names[status] : "未知";
    }
}
