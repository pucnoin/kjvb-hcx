package com.example.stresstest;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final String DEG = "°C";
    private static final String[] THERMAL_NAMES =
            {"None", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown"};

    private static final double THROTTLE_BELOW_PERCENT = 85.0;
    private static final float BATTERY_LIMIT_C = 45.0f;
    private static final float SOC_LIMIT_C = 95.0f;

    // Intensity levels: Low, Medium, High, Extreme
    private static final double[] CPU_FRACTION = {0.25, 0.50, 0.75, 1.00};
    private static final float[] GPU_ITERATIONS = {64f, 192f, 400f, 800f};
    private static final int[] GPU_DRAWS = {2, 4, 8, 16};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CpuStress cpuStress = new CpuStress();
    private final GpuStressRenderer renderer = new GpuStressRenderer();
    private final ThrottleDetector cpuDet = new ThrottleDetector();
    private final ThrottleDetector gpuDet = new ThrottleDetector();
    private final RootThermal root = new RootThermal();
    private TempReader temps;

    private GLSurfaceView glView;
    private ChartView chart;
    private RadioGroup rgTarget, rgIntensity;
    private CheckBox cbSafety, cbRoot;
    private Button btnStart;
    private TextView tvTime, tvThrottle, tvCpuTemp, tvGpuTemp, tvBatTemp, tvStats, tvInfo, tvSensors;

    private boolean running;
    private long startMs;
    private long lastBlocks;
    private boolean curCpu, curGpu;
    private int curThreads;
    private int tickCount;
    private final float[] peak = {Float.NaN, Float.NaN, Float.NaN};

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rgTarget = (RadioGroup) findViewById(R.id.rgTarget);
        rgIntensity = (RadioGroup) findViewById(R.id.rgIntensity);
        cbSafety = (CheckBox) findViewById(R.id.cbSafety);
        cbRoot = (CheckBox) findViewById(R.id.cbRoot);
        btnStart = (Button) findViewById(R.id.btnStart);
        tvTime = (TextView) findViewById(R.id.tvTime);
        tvThrottle = (TextView) findViewById(R.id.tvThrottle);
        tvCpuTemp = (TextView) findViewById(R.id.tvCpuTemp);
        tvGpuTemp = (TextView) findViewById(R.id.tvGpuTemp);
        tvBatTemp = (TextView) findViewById(R.id.tvBatTemp);
        tvStats = (TextView) findViewById(R.id.tvStats);
        tvInfo = (TextView) findViewById(R.id.tvInfo);
        tvSensors = (TextView) findViewById(R.id.tvSensors);
        chart = (ChartView) findViewById(R.id.chart);
        glView = (GLSurfaceView) findViewById(R.id.glView);

        glView.setEGLContextClientVersion(2);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        temps = new TempReader();

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running) {
                    stopTest(null);
                } else {
                    startTest();
                }
            }
        });

        cbRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cbRoot.isChecked()) root.start();
                else root.stop();
            }
        });

        RadioGroup.OnCheckedChangeListener changed = new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (running) applyLoad(); // switch load live, timer keeps going
            }
        };
        rgTarget.setOnCheckedChangeListener(changed);
        rgIntensity.setOnCheckedChangeListener(changed);
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        if (cbRoot.isChecked()) root.start();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        // Leaving the app stops the test (the GPU view pauses anyway).
        if (running) {
            stopTest("Stopped because the app went to the background.");
        }
        handler.removeCallbacks(ticker);
        root.stop();
        glView.onPause();
        super.onPause();
    }

    // ------------------------------------------------------------------ control

    private int intensityLevel() {
        int id = rgIntensity.getCheckedRadioButtonId();
        if (id == R.id.rbLow) return 0;
        if (id == R.id.rbHigh) return 2;
        if (id == R.id.rbExtreme) return 3;
        return 1;
    }

    private void startTest() {
        running = true;
        startMs = SystemClock.elapsedRealtime();
        for (int i = 0; i < peak.length; i++) peak[i] = Float.NaN;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnStart.setText("STOP STRESS TEST");
        applyLoad();
        tick();
    }

    private void applyLoad() {
        int id = rgTarget.getCheckedRadioButtonId();
        curCpu = id != R.id.rbGpu;
        curGpu = id != R.id.rbCpu;
        int level = intensityLevel();

        if (curCpu) {
            int cores = Runtime.getRuntime().availableProcessors();
            curThreads = Math.max(1, (int) Math.round(cores * CPU_FRACTION[level]));
            cpuStress.start(curThreads);
        } else {
            cpuStress.stop();
        }

        renderer.iterations = GPU_ITERATIONS[level];
        renderer.draws = GPU_DRAWS[level];
        renderer.active = curGpu;
        glView.setRenderMode(curGpu ? GLSurfaceView.RENDERMODE_CONTINUOUSLY
                : GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        if (!curGpu) glView.requestRender();

        cpuDet.reset();
        gpuDet.reset();
        lastBlocks = cpuStress.blocks();
        renderer.takeAvgFrameMs();
    }

    private void stopTest(String reason) {
        running = false;
        cpuStress.stop();
        renderer.active = false;
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        glView.requestRender();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnStart.setText("START STRESS TEST");
        if (reason != null) {
            tvThrottle.setText(reason);
            tvThrottle.setTextColor(Color.parseColor("#FFB74D"));
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
        } else {
            tvThrottle.setText("Not running");
            tvThrottle.setTextColor(Color.parseColor("#8FA3B5"));
        }
    }

    // ------------------------------------------------------------------ sampling

    private void tick() {
        tickCount++;
        Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        float batT = batteryTemp(bi);

        float cpuT = (float) temps.readCpu();
        boolean approx = temps.isCpuApprox();
        float gpuT = (float) temps.readGpu();
        if (cbRoot.isChecked()) {
            double rc = root.cpu;
            double rg = root.gpu;
            if (!Double.isNaN(rc)) {
                cpuT = (float) rc;
                approx = false;
            }
            if (!Double.isNaN(rg)) gpuT = (float) rg;
        }

        chart.push(cpuT, gpuT, batT);
        tvCpuTemp.setText((approx && !Float.isNaN(cpuT) ? "~" : "") + fmt(cpuT));
        tvGpuTemp.setText(fmt(gpuT));
        tvBatTemp.setText(fmt(batT));

        float[] now = {cpuT, gpuT, batT};
        if (running) {
            for (int i = 0; i < 3; i++) {
                if (!Float.isNaN(now[i]) && (Float.isNaN(peak[i]) || now[i] > peak[i])) peak[i] = now[i];
            }
        }
        updateStats();

        StringBuilder info = new StringBuilder();
        long elapsed = 0;

        if (running) {
            elapsed = (SystemClock.elapsedRealtime() - startMs) / 1000;
            tvTime.setText(String.format(Locale.US, "%02d:%02d:%02d",
                    elapsed / 3600, (elapsed / 60) % 60, elapsed % 60));

            // Safety stop
            if (cbSafety.isChecked()) {
                if (!Float.isNaN(batT) && batT >= BATTERY_LIMIT_C) {
                    stopTest(String.format(Locale.US, "Safety stop: battery reached %.1f %s", batT, DEG));
                    return;
                }
                if ((!Float.isNaN(cpuT) && cpuT >= SOC_LIMIT_C) || (!Float.isNaN(gpuT) && gpuT >= SOC_LIMIT_C)) {
                    float hot = Float.isNaN(cpuT) ? gpuT : (Float.isNaN(gpuT) ? cpuT : Math.max(cpuT, gpuT));
                    stopTest(String.format(Locale.US, "Safety stop: CPU/GPU reached %.1f %s", hot, DEG));
                    return;
                }
            }

            // Speed measurements
            if (curCpu) {
                long b = cpuStress.blocks();
                cpuDet.feed(b - lastBlocks);
                lastBlocks = b;
                info.append(String.format(Locale.US, "CPU load : %d threads, speed %s of peak\n",
                        curThreads, pct(cpuDet)));
            }
            if (curGpu) {
                double ms = renderer.takeAvgFrameMs();
                if (!Double.isNaN(ms) && ms > 0) gpuDet.feed(1000.0 / ms);
                info.append(String.format(Locale.US, "GPU load : %d passes/frame, speed %s of peak\n",
                        renderer.draws, pct(gpuDet)));
                if (renderer.error != null) info.append(renderer.error).append('\n');
            }
        }

        float headroom = thermalHeadroom();
        int thermal = thermalStatus();
        if (running) updateThrottleText(elapsed, thermal, headroom);

        double[] f = TempReader.readCpuFreqMhz();
        if (f != null) {
            info.append(String.format(Locale.US, "CPU clock: %.2f / %.2f GHz\n", f[0] / 1000.0, f[1] / 1000.0));
        }
        if (thermal >= 0) {
            info.append("System thermal status: ").append(THERMAL_NAMES[Math.min(thermal, 6)]).append('\n');
        } else {
            info.append("System thermal status: needs Android 10+\n");
        }
        if (!Float.isNaN(headroom)) {
            info.append(String.format(Locale.US, "Thermal headroom: %.2f (1.00 = throttle limit)\n", headroom));
        } else {
            info.append("Thermal headroom: needs Android 11+\n");
        }
        info.append(batteryInfo(bi)).append('\n');
        tvInfo.setText(info.toString());

        if (tickCount % 5 == 1) {
            tvSensors.setText(temps.describe() + "Root: " + root.status);
        }
    }

    private void updateThrottleText(long elapsed, int thermal, float headroom) {
        StringBuilder why = new StringBuilder();

        boolean cpuSlow = curCpu && cpuDet.confident() && cpuDet.percentOfPeak() < THROTTLE_BELOW_PERCENT;
        boolean gpuSlow = curGpu && gpuDet.confident() && gpuDet.percentOfPeak() < THROTTLE_BELOW_PERCENT;
        if (cpuSlow) why.append(String.format(Locale.US, "\nCPU speed down to %.0f%%", cpuDet.percentOfPeak()));
        if (gpuSlow) why.append(String.format(Locale.US, "\nGPU speed down to %.0f%%", gpuDet.percentOfPeak()));
        if (thermal >= 2) why.append("\nSystem thermal status: ").append(THERMAL_NAMES[Math.min(thermal, 6)]);
        if (!Float.isNaN(headroom) && headroom >= 1.0f) {
            why.append(String.format(Locale.US, "\nThermal headroom used up (%.2f)", headroom));
        }

        if (why.length() > 0) {
            tvThrottle.setText("⚠ THROTTLING" + why);
            tvThrottle.setTextColor(Color.parseColor("#FF5252"));
        } else if (thermal == 1 || (!Float.isNaN(headroom) && headroom >= 0.85f)) {
            tvThrottle.setText("Getting close to throttling");
            tvThrottle.setTextColor(Color.parseColor("#FFD54F"));
        } else if (elapsed < 15) {
            tvThrottle.setText("Measuring baseline speed...");
            tvThrottle.setTextColor(Color.parseColor("#8FA3B5"));
        } else {
            tvThrottle.setText("No throttling detected");
            tvThrottle.setTextColor(Color.parseColor("#69F0AE"));
        }
    }

    private void updateStats() {
        StringBuilder sb = new StringBuilder("Last 2 min (" + DEG + ")\n");
        sb.append(statLine("CPU    ", chart.stats(0))).append('\n');
        sb.append(statLine("GPU    ", chart.stats(1))).append('\n');
        sb.append(statLine("Battery", chart.stats(2)));
        if (!Float.isNaN(peak[0]) || !Float.isNaN(peak[1]) || !Float.isNaN(peak[2])) {
            sb.append("\nPeak since start:  CPU ").append(fmt(peak[0]))
                    .append("  GPU ").append(fmt(peak[1]))
                    .append("  Bat ").append(fmt(peak[2]));
        }
        tvStats.setText(sb.toString());
    }

    private static String statLine(String name, float[] s) {
        if (s == null) return name + "  N/A";
        return String.format(Locale.US, "%s  min %5.1f  avg %5.1f  max %5.1f", name, s[0], s[1], s[2]);
    }

    // ------------------------------------------------------------------ helpers

    private static String pct(ThrottleDetector d) {
        double p = d.percentOfPeak();
        if (Double.isNaN(p)) return "(measuring)";
        return String.format(Locale.US, "%.0f%%", p);
    }

    private static String fmt(float v) {
        if (Float.isNaN(v)) return "N/A";
        return String.format(Locale.US, "%.1f%s", v, DEG);
    }

    /** Battery temperature has 0.1 degree resolution on Android. */
    private static float batteryTemp(Intent i) {
        if (i == null) return Float.NaN;
        int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (t == Integer.MIN_VALUE) return Float.NaN;
        return t / 10f;
    }

    private static String batteryInfo(Intent i) {
        if (i == null) return "Battery: n/a";
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        StringBuilder sb = new StringBuilder("Battery: ");
        if (level >= 0 && scale > 0) sb.append(level * 100 / scale).append("%");
        sb.append(charging ? ", charging (adds heat)" : ", on battery");
        if (mv > 0) sb.append(String.format(Locale.US, ", %.2f V", mv / 1000.0));
        return sb.toString();
    }

    /** Android 10+ only. Returns -1 when not supported. */
    private int thermalStatus() {
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) return pm.getCurrentThermalStatus();
        }
        return -1;
    }

    /** Android 11+ only. 1.0 means the phone starts to throttle. NaN when unavailable. */
    private float thermalHeadroom() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) return pm.getThermalHeadroom(0);
            } catch (Exception ignored) {
            }
        }
        return Float.NaN;
    }
}
