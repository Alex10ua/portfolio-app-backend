package com.dev.alex.Model.NonDbModel.Ai;

import com.dev.alex.Model.SharesOutstandingHistory.SharesHistoryEntry;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.Splits;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Dividend, split and share-count history for one ticker, plus the derived
 * dividend-growth figures the Historical page shows.
 * <p>
 * {@code sharesHistory} is sparse by design — a point exists only where the
 * count changed, so carry the last value forward rather than interpolating.
 */
public record AiTickerHistorical(
        String ticker,
        String name,
        /** Quote currency of the dividend amounts; may be "GBp" (pence). */
        String currency,
        List<Dividend> dividends,
        List<Splits> splits,
        List<SharesHistoryEntry> sharesHistory,
        List<DividendYear> dividendsByYear,
        /** Consecutive complete years of dividend growth, counted back from the last one. */
        int growthStreakYears,
        LastRaise lastRaise,
        /** Product of every split ratio in the series — multiply an old share count by this. */
        BigDecimal cumulativeSplitFactor) {

    /**
     * One year of dividends. A year is {@code partial} when it is the current
     * year or has fewer payments than the ticker's usual cadence; those are
     * excluded from year-over-year growth, since a half-collected year would
     * otherwise read as a dividend cut.
     */
    public record DividendYear(
            int year,
            BigDecimal total,
            int payments,
            boolean partial,
            /** Growth vs the previous complete year, in percent; null when incomparable. */
            BigDecimal yoyPercent) {
    }

    public record LastRaise(
            LocalDate date,
            BigDecimal fromAmount,
            BigDecimal toAmount,
            BigDecimal percent) {
    }
}
