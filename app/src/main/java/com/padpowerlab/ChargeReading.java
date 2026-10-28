package com.padpowerlab;

import java.util.HashMap;
import java.util.Map;

/** Driver readings only: negotiated limits are never substituted for live input. */
final class ChargeReading {
    final double volts, amps, watts;
    final String protocol, usbType;
    final boolean online;
    final long timestampMillis, elapsedMillis;

    private ChargeReading(double v, double a, String p, String type, boolean connected, long wall, long elapsed) {
        online = connected; volts = v; amps = a;
        watts = Double.isFinite(v) && Double.isFinite(a) ? v * a : Double.NaN;
        protocol = p; usbType = type; timestampMillis = wall; elapsedMillis = elapsed;
    }

    static ChargeReading parse(String text, long wall, long elapsed) {
        if (!text.contains("POWERLAB_OK")) throw new IllegalArgumentException("未读到 USB 充电驱动");
        Map<String,String> values = new HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) values.put(line.substring(0,eq),line.substring(eq+1).trim());
        }
        // Require an explicit online flag. Disconnected drivers may retain old VBUS readings.
        boolean connected = "1".equals(values.get("POWER_SUPPLY_ONLINE"));
        String type = clean(values.get("POWER_SUPPLY_TYPE"));
        String protocol = clean(values.get("PL_REAL_TYPE"));
        if (protocol.isEmpty() || "Unknown".equalsIgnoreCase(protocol)) protocol = type;
        double v = connected ? micro(values.get("POWER_SUPPLY_VOLTAGE_NOW"),60_000_000) : Double.NaN;
        double a = connected ? micro(values.get("POWER_SUPPLY_CURRENT_NOW"),20_000_000) : Double.NaN;
        if (v == 0) v = Double.NaN;
        return new ChargeReading(v,a,protocol,type,connected,wall,elapsed);
    }

    private static double micro(String value, long limit) {
        try { long n=Long.parseLong(value); return n>=0 && n<=limit ? n/1_000_000.0 : Double.NaN; }
        catch (Exception ignored) { return Double.NaN; }
    }
    private static String clean(String value) {
        if (value==null) return "";
        String safe=value.replaceAll("[^A-Za-z0-9_+. /-]", "");
        return safe.substring(0,Math.min(64,safe.length()));
    }
    String protocolLabel() {
        if (!online) return "未连接";
        if (protocol.equals("PD_PPS") || protocol.equals("USB_PD_PPS")) return "PD PPS";
        if (protocol.equals("USB_PD") || protocol.equals("PD")) return "USB PD";
        return protocol.isEmpty() ? "未上报" : protocol;
    }
}
