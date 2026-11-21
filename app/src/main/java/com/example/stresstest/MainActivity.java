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
    private TempReader temps;

    private GLSurfaceView glView;
    private ChartView chart;
    private RadioGroup rgTarget, rgIntensity;
    private CheckBox cbSafety;
    private Button btnStart;
    private TextView tvTime, tvThrottle, tvCpuTemp, tvGpuTemp, tvBatTemp, tvStats, tvInfo, tvSensors;

    private boolean running;
    private long startMs;
    private long lastBlocks;
    private boolean curCpu, curGpu;
    private int curThreads;
    private String stopReason;

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
        tvSensors.setText(temps.describe());

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
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        // Leaving the app stops the test (the GPU view pauses anyway).
        if (running) {
            stopTest("Stopped because the app went to the background.");
        }
        handler.removeCallbacks(ticker);
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
        stopReason = null;
        startMs = SystemClock.elapsedRealtime();
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
        stopReason = reason;
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
        float cpuT = (float) temps.readCpu();
        float gpuT = (float) temps.readGpu();
        float batT = readBatteryTemp();

        chart.push(cpuT, gpuT, batT);
        tvCpuTemp.setText(fmt(cpuT));
        tvGpuTemp.setText(fmt(gpuT));
        tvBatTemp.setText(fmt(batT));
        updateStats();

        StringBuilder info = new StringBuilder();

        if (running) {
            long elapsed = (SystemClock.elapsedRealtime() - startMs) / 1000;
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
            updateThrottleText(elapsed);
        } else if (stopReason == null) {
            // idle: monitor only
        }

        double[] f = TempReader.readCpuFreqMhz();
        if (f != null) {
            info.append(String.format(Locale.US, "CPU clock: %.2f / %.2f GHz\n", f[0] / 1000.0, f[1] / 1000.0));
        }
        int thermal = thermalStatus();
        if (thermal >= 0) {
            info.append("System thermal status: ").append(THERMAL_NAMES[Math.min(thermal, 6)]).append('\n');
        } else {
            info.append("System thermal status: not available on this Android version\n");
        }
        tvInfo.setText(info.toString());
    }

    private void updateThrottleText(long elapsed) {
        int thermal = thermalStatus();
        StringBuilder why = new StringBuilder();

        boolean cpuSlow = curCpu && cpuDet.confident() && cpuDet.percentOfPeak() < THROTTLE_BELOW_PERCENT;
        boolean gpuSlow = curGpu && gpuDet.confident() && gpuDet.percentOfPeak() < THROTTLE_BELOW_PERCENT;
        if (cpuSlow) why.append(String.format(Locale.US, "\nCPU speed down to %.0f%%", cpuDet.percentOfPeak()));
        if (gpuSlow) why.append(String.format(Locale.US, "\nGPU speed down to %.0f%%", gpuDet.percentOfPeak()));
        if (thermal >= 2) why.append("\nSystem thermal status: ").append(THERMAL_NAMES[Math.min(thermal, 6)]);

        if (why.length() > 0) {
            tvThrottle.setText("⚠ THROTTLING" + why);
            tvThrottle.setTextColor(Color.parseColor("#FF5252"));
        } else if (thermal == 1) {
            tvThrottle.setText("Light thermal pressure (no throttling yet)");
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
    private float readBatteryTemp() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return Float.NaN;
        int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (t == Integer.MIN_VALUE) return Float.NaN;
        return t / 10f;
    }

    /** Android 10+ only. Returns -1 when not supported. */
    private int thermalStatus() {
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) return pm.getCurrentThermalStatus();
        }
        return -1;
    }
}
