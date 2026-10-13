package com.keevo.admin.platform.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * LatencyPercentiles — overall percentile of several timers (Story 9-5).
 *
 * <p>Client-side percentiles are computed per timer (one per URI / status / method) and cannot be
 * summed. Each timer's distribution is approximated by a piecewise-linear CDF through its known
 * points — (0, 0), (p50, 0.5), (p95, 0.95), (p99, 0.99), (max, 1) — and the timers are mixed with
 * their request count of the window as weight. The overall percentile {@code q} is the smallest
 * latency where the mixed CDF reaches {@code q} (linear between knots).
 */
final class LatencyPercentiles {

    /** One timer: its weight (requests in the window) and its known percentiles. */
    record Weighted(long weight, Double p50, Double p95, Double p99, Double max) {}

    private LatencyPercentiles() {}

    /** @return the overall {@code q} percentile in ms, or null when no timer has weight and data */
    static Double merge(List<Weighted> timers, double q) {
        List<double[][]> cdfs = new ArrayList<>();
        List<Long> weights = new ArrayList<>();
        TreeSet<Double> knots = new TreeSet<>();
        for (Weighted t : timers) {
            if (t.weight() <= 0) continue;
            double[][] cdf = cdf(t);
            if (cdf == null) continue;
            cdfs.add(cdf);
            weights.add(t.weight());
            for (double[] point : cdf) knots.add(point[0]);
        }
        if (cdfs.isEmpty()) return null;

        long total = weights.stream().mapToLong(Long::longValue).sum();
        double prevX = 0;
        double prevF = 0;
        for (double x : knots) {
            double f = 0;
            for (int i = 0; i < cdfs.size(); i++) {
                f += weights.get(i) * eval(cdfs.get(i), x);
            }
            f /= total;
            if (f >= q - 1e-9) {
                if (f - prevF <= 1e-12) return x;
                return prevX + (x - prevX) * (q - prevF) / (f - prevF);
            }
            prevX = x;
            prevF = f;
        }
        return knots.last();
    }

    /** Monotonic knots of one timer, or null when it has no usable percentile. */
    private static double[][] cdf(Weighted t) {
        double[] probs = {0.5, 0.95, 0.99, 1.0};
        Double[] values = {t.p50(), t.p95(), t.p99(), t.max()};
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{0, 0});
        double last = 0;
        for (int i = 0; i < probs.length; i++) {
            Double v = values[i];
            if (v == null || v.isNaN() || v <= 0) continue;
            last = Math.max(last, v);
            points.add(new double[]{last, probs[i]});
        }
        if (points.size() == 1) return null;
        double[] top = points.get(points.size() - 1);
        if (top[1] < 1.0) points.add(new double[]{top[0], 1.0});
        return points.toArray(new double[0][]);
    }

    private static double eval(double[][] cdf, double x) {
        if (x <= cdf[0][0]) return cdf[0][1];
        for (int i = 1; i < cdf.length; i++) {
            if (x < cdf[i][0]) {
                double x0 = cdf[i - 1][0];
                double f0 = cdf[i - 1][1];
                return f0 + (cdf[i][1] - f0) * (x - x0) / (cdf[i][0] - x0);
            }
            if (x == cdf[i][0]) {
                double f = cdf[i][1];
                // several knots at the same latency: take the highest probability
                while (i + 1 < cdf.length && cdf[i + 1][0] == x) f = cdf[++i][1];
                return f;
            }
        }
        return 1.0;
    }
}
