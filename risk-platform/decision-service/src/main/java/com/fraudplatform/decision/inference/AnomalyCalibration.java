package com.fraudplatform.decision.inference;

/**
 * Maps a raw anomaly score to its percentile in the training distribution by piecewise-linear
 * interpolation — a line-by-line port of {@code numpy.interp} (including its handling of repeated
 * x-values), so Java and Python produce identical percentiles.
 */
public final class AnomalyCalibration {

    private AnomalyCalibration() {
    }

    public static double percentile(double x, double[] xp, double[] fp) {
        int n = xp.length;
        if (x < xp[0]) return 0.0;
        if (x > xp[n - 1]) return 1.0;
        int j = lastIndexLessOrEqual(xp, x);
        if (j == n - 1) return fp[n - 1];
        if (xp[j] == x) return fp[j];
        double slope = (fp[j + 1] - fp[j]) / (xp[j + 1] - xp[j]);
        return slope * (x - xp[j]) + fp[j];
    }

    /** Largest j with xp[j] <= x (xp non-decreasing, xp[0] <= x). */
    static int lastIndexLessOrEqual(double[] xp, double x) {
        int lo = 0;
        int hi = xp.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (xp[mid] <= x) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }
}
