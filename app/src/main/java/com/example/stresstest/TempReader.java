package com.example.stresstest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads CPU/GPU temperatures from /sys/class/thermal. Which sensors exist
 * (and whether apps may read them) depends on the phone and Android version,
 * so everything here is "best effort" and returns NaN when unavailable.
 */
public class TempReader {

    private static class Zone {
        String type;
        File file;
        boolean cpu;
        boolean gpu;
    }

    private final List<Zone> zones = new ArrayList<>();

    public TempReader() {
        discover();
    }

    private void discover() {
        for (int i = 0; i < 100; i++) {
            File dir = new File("/sys/class/thermal/thermal_zone" + i);
            File tempFile = new File(dir, "temp");
            if (!tempFile.exists()) continue;
            if (readLine(tempFile) == null) continue; // not readable by apps

            String type = readLine(new File(dir, "type"));
            if (type == null) type = "zone" + i;
            String t = type.toLowerCase(Locale.US);

            Zone z = new Zone();
            z.type = type;
            z.file = tempFile;
            z.gpu = t.contains("gpu") || t.contains("g3d") || t.contains("mali") || t.contains("adreno");
            z.cpu = !z.gpu && (t.contains("cpu") || t.contains("soc") || t.contains("big")
                    || t.contains("little") || t.contains("cluster") || t.contains("kryo")
                    || t.contains("apc") || t.contains("tsens_tz_sensor"));
            if (t.contains("batt") || t.contains("chg") || t.contains("charg")) z.cpu = false;
            if (z.cpu || z.gpu) zones.add(z);
        }
    }

    /** Hottest CPU-related sensor in °C, or NaN. */
    public double readCpu() {
        return readMax(false);
    }

    /** Hottest GPU-related sensor in °C, or NaN. */
    public double readGpu() {
        return readMax(true);
    }

    private double readMax(boolean gpu) {
        double best = Double.NaN;
        for (Zone z : zones) {
            if (gpu ? !z.gpu : !z.cpu) continue;
            double v = parse(readLine(z.file));
            if (Double.isNaN(v)) continue;
            if (Double.isNaN(best) || v > best) best = v;
        }
        return best;
    }

    /** Short text about which sensors were found. */
    public String describe() {
        int c = 0, g = 0;
        StringBuilder cn = new StringBuilder();
        StringBuilder gn = new StringBuilder();
        for (Zone z : zones) {
            if (z.gpu) {
                if (g++ < 2) gn.append(g > 1 ? ", " : "").append(z.type);
            } else {
                if (c++ < 2) cn.append(c > 1 ? ", " : "").append(z.type);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("CPU sensors: ").append(c == 0 ? "none readable" : c + " (" + cn + (c > 2 ? ", ..." : "") + ")");
        sb.append("\nGPU sensors: ").append(g == 0 ? "none readable" : g + " (" + gn + (g > 2 ? ", ..." : "") + ")");
        sb.append("\nBattery sensor: always available");
        if (c == 0 || g == 0) {
            sb.append("\nNote: many phones (especially Android 10+) block apps from reading CPU/GPU sensors.");
        }
        return sb.toString();
    }

    /**
     * Highest current CPU clock and highest possible clock in MHz, or null
     * if the phone does not let apps read them.
     */
    public static double[] readCpuFreqMhz() {
        double curMax = -1, maxMax = -1;
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < cores; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            double cur = parse1(readLine(new File(base + "scaling_cur_freq")));
            double max = parse1(readLine(new File(base + "cpuinfo_max_freq")));
            if (cur > curMax) curMax = cur;
            if (max > maxMax) maxMax = max;
        }
        if (curMax <= 0 || maxMax <= 0) return null;
        return new double[]{curMax / 1000.0, maxMax / 1000.0};
    }

    private static double parse1(String s) {
        if (s == null) return -1;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Converts raw sysfs numbers (milli-degrees, tenths or degrees) to °C. */
    private static double parse(String s) {
        if (s == null) return Double.NaN;
        double raw;
        try {
            raw = Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        double a = Math.abs(raw);
        double v = a >= 1000 ? raw / 1000.0 : (a >= 150 ? raw / 10.0 : raw);
        if (v <= 1.0 || v >= 150.0) return Double.NaN;
        return v;
    }

    private static String readLine(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            return r.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
