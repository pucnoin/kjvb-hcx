package com.example.stresstest;

/**
 * Detects throttling by watching speed. Feed one "speed" number per second
 * (higher = faster). The detector remembers the best 5-second average and
 * reports the current speed as a percentage of that best value.
 */
public class ThrottleDetector {

    private static final int WINDOW = 5;
    private static final int CONFIDENT_AFTER = 15; // seconds of data before we judge

    private final double[] win = new double[WINDOW];
    private int count;
    private double baseline;
    private double lastAvg;

    public void reset() {
        count = 0;
        baseline = 0;
        lastAvg = 0;
    }

    public void feed(double perf) {
        win[count % WINDOW] = perf;
        count++;
        if (count < WINDOW) return;
        double sum = 0;
        for (double v : win) sum += v;
        lastAvg = sum / WINDOW;
        if (lastAvg > baseline) baseline = lastAvg;
    }

    /** Current speed as % of the best speed seen, or NaN while still measuring. */
    public double percentOfPeak() {
        if (count < WINDOW || baseline <= 0) return Double.NaN;
        return lastAvg / baseline * 100.0;
    }

    /** True once there is enough data to trust the result. */
    public boolean confident() {
        return count >= CONFIDENT_AFTER;
    }
}
