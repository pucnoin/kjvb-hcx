package com.example.stresstest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Optional: on a rooted phone, reads the thermal sensors through "su", which
 * bypasses the restrictions that stop normal apps. Runs in its own thread and
 * keeps one root shell open. Does nothing on phones without root.
 */
public class RootThermal {

    public volatile double cpu = Double.NaN;
    public volatile double gpu = Double.NaN;
    public volatile String status = "off";

    private volatile boolean running;
    private Process proc;

    public synchronized void start() {
        if (running) return;
        running = true;
        status = "starting...";
        new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "root-thermal").start();
    }

    public synchronized void stop() {
        running = false;
        if (proc != null) {
            try {
                proc.destroy();
            } catch (Exception ignored) {
            }
        }
        cpu = Double.NaN;
        gpu = Double.NaN;
        status = "off";
    }

    private void loop() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            synchronized (this) {
                proc = p;
            }
            status = "waiting for root permission...";
            OutputStream os = p.getOutputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String script =
                    "for f in /sys/class/thermal/thermal_zone*; do echo \"$(cat $f/type) $(cat $f/temp)\"; done; "
                            + "echo \"kgsl $(cat /sys/class/kgsl/kgsl-3d0/temp)\"; "
                            + "echo __END__\n";
            while (running) {
                os.write(script.getBytes());
                os.flush();
                double c = Double.NaN;
                double g = Double.NaN;
                String line;
                while ((line = br.readLine()) != null && !line.equals("__END__")) {
                    int idx = line.lastIndexOf(' ');
                    if (idx <= 0) continue;
                    String name = line.substring(0, idx);
                    double v = TempReader.normalize(line.substring(idx + 1));
                    if (Double.isNaN(v)) continue;
                    if (TempReader.isGpuName(name)) {
                        if (Double.isNaN(g) || v > g) g = v;
                    } else if (TempReader.isCpuName(name)) {
                        if (Double.isNaN(c) || v > c) c = v;
                    }
                }
                if (line == null) {
                    status = "root denied or not available";
                    break;
                }
                cpu = c;
                gpu = g;
                status = (Double.isNaN(c) && Double.isNaN(g)) ? "root OK, but no CPU/GPU sensor found" : "root OK";
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            status = "root not available";
        } finally {
            cpu = Double.NaN;
            gpu = Double.NaN;
            running = false;
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
