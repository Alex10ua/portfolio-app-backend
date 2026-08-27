package com.dev.alex.Service.Ai;

import com.dev.alex.Model.NonDbModel.Ai.AiWatchlistRow;
import com.dev.alex.Model.NonDbModel.WatchlistEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Distribution maths over a ticker's own yield history — the same ranking the
 * Yield Target tab does client-side in yieldMath.ts, done here so a model gets
 * the answer instead of a series to average.
 * <p>
 * Yields are ratios, so none of this touches FX.
 */
public final class AiYieldMath {

    /** Lookback windows, in months; null means the whole series. */
    private static final List<Baseline> BASELINES = List.of(
            new Baseline("1Y", 12),
            new Baseline("3Y", 36),
            new Baseline("5Y", 60),
            new Baseline("10Y", 120),
            new Baseline("All", null));

    private record Baseline(String label, Integer months) {
    }

    private AiYieldMath() {
    }

    /**
     * Linear-interpolated quantile over an ascending list, matching the
     * frontend's quantile() so both surfaces rank a yield identically.
     */
    public static BigDecimal quantile(List<BigDecimal> ascending, double q) {
        if (ascending == null || ascending.isEmpty()) return null;
        double i = (ascending.size() - 1) * q;
        int lo = (int) Math.floor(i);
        int hi = (int) Math.ceil(i);
        BigDecimal low = ascending.get(lo);
        if (lo == hi) return low;
        BigDecimal high = ascending.get(hi);
        return low.add(high.subtract(low).multiply(BigDecimal.valueOf(i - lo)))
                .setScale(4, RoundingMode.HALF_EVEN);
    }

    /** Stats for every baseline that has data; empty when the ticker has no history. */
    public static List<AiWatchlistRow.YieldStats> statsFor(WatchlistEntry entry) {
        List<AiWatchlistRow.YieldStats> out = new ArrayList<>();
        List<WatchlistEntry.YieldPoint> history = entry.getYieldHistory();
        if (history == null || history.isEmpty()) return out;

        for (Baseline baseline : BASELINES) {
            List<WatchlistEntry.YieldPoint> window = baseline.months() == null
                    ? history
                    : history.subList(Math.max(0, history.size() - baseline.months()), history.size());
            List<BigDecimal> sorted = window.stream()
                    .map(WatchlistEntry.YieldPoint::getYield)
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (sorted.isEmpty()) continue;

            BigDecimal median = quantile(sorted, 0.5);
            BigDecimal current = entry.getForwardYield();
            out.add(new AiWatchlistRow.YieldStats(
                    baseline.label(),
                    sorted.size(),
                    sorted.get(0),
                    quantile(sorted, 0.25),
                    median,
                    quantile(sorted, 0.75),
                    quantile(sorted, 0.90),
                    sorted.get(sorted.size() - 1),
                    percentileOf(sorted, current),
                    vsMedian(current, median)));
        }
        return out;
    }

    /** Share of the window at or below {@code current}, 0-100. */
    private static BigDecimal percentileOf(List<BigDecimal> ascending, BigDecimal current) {
        if (current == null || ascending.isEmpty()) return null;
        long atOrBelow = ascending.stream().filter(v -> v.compareTo(current) <= 0).count();
        return BigDecimal.valueOf(atOrBelow)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(ascending.size()), 2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal vsMedian(BigDecimal current, BigDecimal median) {
        if (current == null || median == null || median.signum() <= 0) return null;
        return current.subtract(median)
                .multiply(BigDecimal.valueOf(100))
                .divide(median, 2, RoundingMode.HALF_EVEN);
    }
}
