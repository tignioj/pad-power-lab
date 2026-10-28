package com.padpowerlab;

public final class ChargeReadingTest {
    private static ChargeReading read(String values) {
        return ChargeReading.parse(values+"\nPOWERLAB_OK\n",1000,2000);
    }
    private static void near(double actual,double expected) {
        if(Math.abs(actual-expected)>0.000001)throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args) {
        ChargeReading live=read("POWER_SUPPLY_ONLINE=1\nPOWER_SUPPLY_TYPE=USB_PD\nPOWER_SUPPLY_VOLTAGE_NOW=9012000\nPOWER_SUPPLY_CURRENT_NOW=812000\nPL_REAL_TYPE=PD_PPS");
        near(live.volts,9.012);near(live.amps,.812);near(live.watts,7.317744);
        assert live.protocolLabel().equals("PD PPS");
        assert live.timestampMillis==1000 && live.elapsedMillis==2000;

        ChargeReading offline=read("POWER_SUPPLY_ONLINE=0\nPOWER_SUPPLY_VOLTAGE_NOW=9000000\nPOWER_SUPPLY_CURRENT_NOW=1000000\nPL_REAL_TYPE=PD_PPS");
        assert !offline.online && Double.isNaN(offline.volts) && Double.isNaN(offline.watts);
        assert offline.protocolLabel().equals("未连接");

        ChargeReading limits=read("POWER_SUPPLY_ONLINE=1\nPOWER_SUPPLY_TYPE=USB_PD\nPOWER_SUPPLY_VOLTAGE_MAX=20000000\nPOWER_SUPPLY_CURRENT_MAX=5000000");
        assert Double.isNaN(limits.volts) && Double.isNaN(limits.amps) && Double.isNaN(limits.watts);
        assert limits.protocolLabel().equals("USB PD"); // Do not invent PPS from voltages.

        ChargeReading missing=read("POWER_SUPPLY_ONLINE=1\nPOWER_SUPPLY_VOLTAGE_NOW=9000000");
        assert Double.isFinite(missing.volts) && Double.isNaN(missing.watts);
        ChargeReading zero=read("POWER_SUPPLY_ONLINE=1\nPOWER_SUPPLY_VOLTAGE_NOW=9000000\nPOWER_SUPPLY_CURRENT_NOW=0");
        near(zero.watts,0);
        ChargeReading corrupt=read("POWER_SUPPLY_ONLINE=1\nPOWER_SUPPLY_VOLTAGE_NOW=900000000\nPOWER_SUPPLY_CURRENT_NOW=-42");
        assert Double.isNaN(corrupt.volts) && Double.isNaN(corrupt.amps);
        ChargeReading unknownOnline=read("POWER_SUPPLY_VOLTAGE_NOW=9000000\nPOWER_SUPPLY_CURRENT_NOW=1000000");
        assert !unknownOnline.online && Double.isNaN(unknownOnline.watts);
        try {ChargeReading.parse("permission denied",0,0);throw new AssertionError("missing driver marker accepted");}
        catch(IllegalArgumentException expected) {}
        System.out.println("PASS: units, PPS source, offline stale values, missing readings, limits, zero current, invalid values");
    }
}
