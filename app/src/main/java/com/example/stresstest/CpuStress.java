package com.example.stresstest;

import android.os.Process;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Starts N busy threads that run heavy floating point + integer math. */
public class CpuStress {

    private AtomicBoolean currentFlag = new AtomicBoolean(false);
    private final AtomicLong blocks = new AtomicLong();
    private static volatile double sink; // stops the JIT from removing the work

    public synchronized void start(int threadCount) {
        stop();
        final AtomicBoolean flag = new AtomicBoolean(true);
        currentFlag = flag;
        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    work(flag);
                }
            }, "cpu-stress-" + i);
            t.start();
        }
    }

    public synchronized void stop() {
        currentFlag.set(false);
    }

    /** Total number of finished work blocks (all threads). Used to measure speed. */
    public long blocks() {
        return blocks.get();
    }

    private void work(AtomicBoolean flag) {
        // Slightly lower priority keeps the UI responsive while all cores are busy.
        Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE);
        double x = 0.5 + Math.random();
        long n = System.nanoTime();
        while (flag.get()) {
            for (int i = 0; i < 20000; i++) {
                x = Math.sin(x) * Math.cos(x) + Math.sqrt(x + 1.0) + Math.atan(x);
                n = n * 6364136223846793005L + 1442695040888963407L;
                x += (n >>> 60) * 1e-9;
            }
            sink = x + n;
            blocks.incrementAndGet();
        }
    }
}
