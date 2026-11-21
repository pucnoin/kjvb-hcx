package com.example.stresstest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads CPU/GPU temperatures from the kernel files that Android phones expose
 * (thermal zones, hwmon, and some vendor-specific files). Which files exist, and
 * whether normal apps may read them, depends on the phone and Android version.
 * Everything is "best effort" and returns NaN when nothing is readable.
 */
public class TempReader {

    private static class Src {
        String name;
        File file;
        boolean cpu;
        boolean gpu;
    }

    private final List<Src> srcs = new ArrayList<Src>();   // clearly CPU or GPU
    private final List<Src> other = new ArrayList<Src>();  // readable, but unknown purpose
    private int found;
    private int blocked;
    private volatile boolean cpuApprox;

    public TempReader() {
        discover();
    }

    // ------------------------------------------------------------ classification

    public static boolean isGpuName(String name) {
        String t = name.toLowerCase(Locale.US);
        return t.contains("gpu") || t.contains("g3d") || t.contains("mali")
                || t.contains("adreno") || t.contains("kgsl");
    }

    public static boolean isCpuName(String name) {
        if (isGpuName(name)) return false;
        String t = name.toLowerCase(Locale.US);
        if (t.contains("batt") || t.contains("chg") || t.contains("charg")) return false;
        return t.contains("cpu") || t.contains("soc") || t.contains("big") || t.contains("little")
                || t.contains("cluster") || t.contains("kryo") || t.contains("apc")
                || t.contains("tmu") || t.contains("tsens_tz_sensor");
    }

    /** Turns raw text (milli-degrees, tenths or degrees) into degrees C, or NaN. */
    public static double normalize(String s) {
        if (s == null) return Double.NaN;
        double raw;
        try {
            raw = Double.parseDouble(s.trim().split("[,\\s]+")[0]);
        } catch (Exception e) {
            return Double.NaN;
        }
        double a = Math.abs(raw);
        double v = a >= 1000 ? raw / 1000.0 : (a >= 150 ? raw / 10.0 : raw);
        if (v <= 1.0 || v >= 150.0) return Double.NaN;
        return v;
    }

    // ------------------------------------------------------------------ discovery

    private void discover() {
        // 1) Thermal zones (try both common locations).
        String[] roots = {"/sys/class/thermal", "/sys/devices/virtual/thermal"};
        for (int i = 0; i < 100; i++) {
            for (String root : roots) {
                File dir = new File(root, "thermal_zone" + i);
                File tf = new File(dir, "temp");
                if (readLine(tf) == null) {
                    if (tf.exists()) {
                        found++;
                        blocked++;
                    }
                    continue;
                }
                found++;
                String type = readLine(new File(dir, "type"));
                add(type == null ? "zone" + i : type.trim(), tf);
                break;
            }
        }

        // 2) hwmon sensors.
        for (int h = 0; h < 10; h++) {
            File d = new File("/sys/class/hwmon/hwmon" + h);
            String name = readLine(new File(d, "name"));
            for (int k = 1; k <= 4; k++) {
                File tf = new File(d, "temp" + k + "_input");
                if (readLine(tf) == null) {
                    if (tf.exists()) {
                        found++;
                        blocked++;
                    }
                    continue;
                }
                found++;
                add((name == null ? "hwmon" + h : name.trim()) + "/temp" + k, tf);
            }
        }

        // 3) Vendor-specific files (Exynos, MediaTek, Adreno, old kernels).
        String[][] extras = {
                {"cpu_temp", "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp"},
                {"cpu_temp_alt", "/sys/devices/system/cpu/cpu0/cpufreq/FakeShmoo_cpu_temp"},
                {"tmu", "/sys/devices/10060000.tmu/temp"},
                {"tmu", "/sys/devices/platform/10060000.tmu/temp"},
                {"cpu_mtk", "/proc/mtktz/mtktscpu"},
                {"cpu_mtk_ap", "/proc/mtktz/mtktsAP"},
                {"gpu_kgsl", "/sys/class/kgsl/kgsl-3d0/temp"},
        };
        for (String[] e : extras) {
            File f = new File(e[1]);
            if (readLine(f) == null) {
                if (f.exists()) {
                    found++;
                    blocked++;
                }
                continue;
            }
            found++;
            add(e[0], f);
        }
    }

    private void add(String name, File file) {
        Src s = new Src();
        s.name = name;
        s.file = file;
        s.gpu = isGpuName(name);
        s.cpu = isCpuName(name);
        if (s.cpu || s.gpu) srcs.add(s);
        else other.add(s);
    }

    // -------------------------------------------------------------------- reading

    /** Hottest CPU sensor in C. If none is clearly a CPU sensor, uses another sensor (approximate). */
    public double readCpu() {
        double v = readMax(false);
        cpuApprox = false;
        if (Double.isNaN(v)) {
            v = readMaxOther();
            if (!Double.isNaN(v)) cpuApprox = true;
        }
        return v;
    }

    public double readGpu() {
        return readMax(true);
    }

    /** True when the last CPU value came from a sensor that is only probably the CPU. */
    public boolean isCpuApprox() {
        return cpuApprox;
    }

    private double readMax(boolean gpu) {
        double best = Double.NaN;
        for (Src s : srcs) {
            if (gpu ? !s.gpu : !s.cpu) continue;
            double v = normalize(readLine(s.file));
            if (Double.isNaN(v)) continue;
            if (Double.isNaN(best) || v > best) best = v;
        }
        return best;
    }

    private double readMaxOther() {
        double best = Double.NaN;
        for (Src s : other) {
            String t = s.name.toLowerCase(Locale.US);
            if (t.contains("batt") || t.contains("chg") || t.contains("charg") || t.contains("usb")
                    || t.contains("pm") || t.contains("bms") || t.contains("ambient")) continue;
            double v = normalize(readLine(s.file));
            if (Double.isNaN(v)) continue;
            if (Double.isNaN(best) || v > best) best = v;
        }
        return best;
    }

    /** Human readable report: what was found, what is blocked, and live values. */
    public String describe() {
        int c = 0, g = 0;
        for (Src s : srcs) {
            if (s.gpu) g++;
            else c++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("SENSOR REPORT\n");
        sb.append("Thermal files found: ").append(found)
                .append(", blocked by Android: ").append(blocked).append('\n');
        sb.append("CPU sensors: ").append(c).append("  GPU sensors: ").append(g)
                .append("  other: ").append(other.size()).append('\n');
        int shown = 0;
        for (Src s : srcs) {
            if (shown++ >= 14) break;
            sb.append("  ").append(s.gpu ? "[GPU] " : "[CPU] ").append(s.name).append(" = ")
                    .append(valueText(s)).append('\n');
        }
        for (Src s : other) {
            if (shown++ >= 14) break;
            sb.append("  [?] ").append(s.name).append(" = ").append(valueText(s)).append('\n');
        }
        if (srcs.isEmpty() && other.isEmpty()) {
            sb.append("Android blocks apps from reading CPU/GPU sensors on this phone.\n");
            sb.append("(Normal on Android 10+.) Tick the root option if the phone is rooted.\n");
        } else if (c == 0 && !other.isEmpty()) {
            sb.append("No sensor is clearly named CPU - an approximate one is used (shown with ~).\n");
        }
        return sb.toString();
    }

    private String valueText(Src s) {
        double v = normalize(readLine(s.file));
        return Double.isNaN(v) ? "n/a" : String.format(Locale.US, "%.1f", v);
    }

    // ---------------------------------------------------------------- CPU clocks

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
