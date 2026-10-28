package com.padpowerlab;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Optional, foreground-only, read-only root collection. No charger settings are written. */
final class RootChargeMonitor implements AutoCloseable {
    static final String SCRIPT = "cat /sys/class/power_supply/usb/uevent 2>/dev/null || exit 12; "
        + "for f in /sys/class/qcom-battery/usb_real_type /sys/class/qcom-battery/real_type; do "
        + "if [ -r \"$f\" ]; then printf '\\nPL_REAL_TYPE='; cat \"$f\"; break; fi; done; "
        + "printf '\\nPOWERLAB_OK\\n'";
    private static final String SESSION_SCRIPT="while read -r request; do [ \"$request\" = sample ] || exit 0; "+SCRIPT+"; done";
    volatile boolean enabled, everSucceeded;
    volatile ChargeReading latest;
    volatile String status = "disabled";
    private volatile boolean foreground, closed;
    private volatile Process process;
    private long generation;
    private final Object wake = new Object();
    private final Runnable changed;
    private final Thread worker;

    RootChargeMonitor(Runnable onChange) {
        changed = onChange;
        worker = new Thread(this::loop,"PowerLab-Charge"); worker.start();
    }
    void setEnabled(boolean value) {
        synchronized(wake) {
            enabled=value;
            generation++;
            latest=null;
            status=value ? "awaiting_permission" : "disabled";
            if (!value && process!=null) process.destroy();
            wake.notifyAll();
        }
        changed.run();
    }
    void setForeground(boolean value) { synchronized(wake) { foreground=value; wake.notifyAll(); } }
    private void loop() {
        while(!closed) {
            try {
                synchronized(wake) { while(!closed && (!enabled || !foreground))wake.wait(); }
                if (closed) break;
                collectSession();
            } catch(InterruptedException ignored) { if(closed)break; }
        }
    }
    private boolean current(long request) { synchronized(wake){return enabled && !closed && generation==request;} }
    private String frame(Process p,InputStream input,long request,long timeout) throws java.io.IOException,InterruptedException {
        long deadline=SystemClock.elapsedRealtime()+timeout;
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] bytes=new byte[1024];
        while(current(request)) {
            int count=input.available();
            if(count>0) {
                count=input.read(bytes,0,Math.min(count,bytes.length));
                if(count<0)throw new java.io.IOException("Root 会话已关闭");
                if(buffer.size()+count>16384)throw new IllegalStateException("驱动输出超出预期");
                buffer.write(bytes,0,count);
                String text=buffer.toString("UTF-8");
                if(text.contains("POWERLAB_OK\n"))return text;
            }else {
                if(!p.isAlive())throw new java.io.IOException("未获 Root 授权或驱动不可读");
                if(SystemClock.elapsedRealtime()>=deadline)throw new java.io.IOException("Root 授权或采样超时");
                Thread.sleep(20);
            }
        }
        throw new InterruptedException("Root 监测已取消");
    }
    private void collectSession() throws InterruptedException {
        Process p=null;
        final long request;
        synchronized(wake) { request=generation; }
        try {
            // Reuse one root session to avoid repeated authorization requests and process overhead.
            p=new ProcessBuilder("su","-c",SESSION_SCRIPT).redirectErrorStream(true).start();
            synchronized(wake) { if(!enabled || closed || generation!=request){p.destroy();return;} process=p; }
            try(InputStream input=p.getInputStream();OutputStream output=p.getOutputStream()) {
                boolean first=true;
                while(current(request) && foreground) {
                    output.write(new byte[]{'s','a','m','p','l','e','\n'});output.flush();
                    String text=frame(p,input,request,first?30000:5000);first=false;
                    ChargeReading reading=ChargeReading.parse(text,System.currentTimeMillis(),SystemClock.elapsedRealtime());
                    synchronized(wake) {
                        if(!current(request))return;
                        latest=reading;everSucceeded=true;status=reading.online?"live":"no_input";
                    }
                    changed.run();
                    synchronized(wake) { if(current(request) && foreground)wake.wait(1000); }
                }
            }
        }catch(java.io.IOException | IllegalStateException | IllegalArgumentException e) {
            synchronized(wake) { if(!enabled || closed || generation!=request)return;enabled=false;latest=null;status="unavailable"; }
        }finally { if(p!=null)p.destroy(); synchronized(wake){if(process==p)process=null;} }
        if(!closed)changed.run();
    }
    ChargeReading fresh(long now) {
        ChargeReading r=latest;
        return enabled && r!=null && now-r.elapsedMillis<=4000 ? r : null;
    }
    String csvStatus(long now) {
        return enabled && latest!=null && fresh(now)==null ? "stale" : status;
    }
    @Override public void close() {
        synchronized(wake) {closed=true;enabled=false;if(process!=null)process.destroy();wake.notifyAll();}
        worker.interrupt();
    }
}
