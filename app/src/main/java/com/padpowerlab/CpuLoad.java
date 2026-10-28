package com.padpowerlab;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** A fresh cancellation token per run prevents old workers from being resurrected. */
final class CpuLoad {
    private AtomicBoolean token = new AtomicBoolean(false);
    private final List<Thread> workers = new ArrayList<>();
    private static volatile double result;

    synchronized void start(int count, int duty) {
        stop();
        if (count == 0 || duty == 0) return;
        final AtomicBoolean active = token = new AtomicBoolean(true);
        for (int n = 0; n < count; n++) {
            final int seed = n;
            Thread worker = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                double[] a = new double[16384];
                for (int i = 0; i < a.length; i++) a[i] = (i + seed + 1) * 0.00001;
                double sum = 0;
                int cursor = 0;
                while (active.get()) {
                    long start = System.nanoTime();
                    long busyUntil = start + duty * 200_000L;
                    do {
                        // Small batches keep the duty cycle accurate even before ART warms up.
                        for (int j = 0; j < 128; j++) {
                            int i = cursor++ & (a.length - 1);
                            a[i] = Math.sin(a[i] * 1.0001 + sum * 0.0000001) + Math.cos(i + sum);
                            sum = sum * 0.9999 + a[i];
                        }
                    } while (active.get() && System.nanoTime() < busyUntil);
                    result = sum;
                    long rest = start + 20_000_000L - System.nanoTime();
                    if (rest > 0) LockSupport.parkNanos(rest);
                }
            }, "PowerLab-CPU-" + n);
            workers.add(worker);
            worker.start();
        }
    }

    synchronized void stop() {
        token.set(false);
        for (Thread t : workers) t.interrupt();
        workers.clear();
    }
}
