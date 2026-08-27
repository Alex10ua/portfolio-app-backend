package com.dev.alex.Model.NonDbModel.Ai;

import com.dev.alex.Model.NonDbModel.WatchlistEntry;

import java.math.BigDecimal;
import java.util.List;

/**
 * One watchlist ticker plus where its current yield sits inside its own
 * historical distribution. The distribution stats are computed here rather than
 * left to the caller: they are pure percentile maths over a ratio series, so no
 * FX is involved and an exact answer is cheap.
 */
public record AiWatchlistRow(
        WatchlistEntry item,
        List<YieldStats> yieldStats) {

    /**
     * Distribution of the trailing-twelve-month yield over one lookback window.
     * All values are percentages, matching {@link WatchlistEntry#getForwardYield()}.
     */
    public record YieldStats(
            /** "1Y" | "3Y" | "5Y" | "10Y" | "All" */
            String baseline,
            int sampleCount,
            BigDecimal min,
            BigDecimal p25,
            BigDecimal median,
            BigDecimal p75,
            BigDecimal p90,
            BigDecimal max,
            /** Where today's forward yield ranks in that window, 0-100. */
            BigDecimal currentPercentile,
            /** Today's forward yield vs the window median, in percent. */
            BigDecimal vsMedianPercent) {
    }
}
