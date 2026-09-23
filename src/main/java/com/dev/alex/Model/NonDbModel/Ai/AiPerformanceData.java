package com.dev.alex.Model.NonDbModel.Ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Portfolio performance. The scalar fields carry an explicit
 * {@code UnconvertedNativeSum} suffix because that is what they are: sums taken
 * across currencies without any FX applied, so they are exact only for a
 * single-currency portfolio. The per-currency maps and the time series are the
 * trustworthy part — each amount carries its currency and can be converted properly.
 */
public record AiPerformanceData(
        String period,
        BigDecimal totalInvestedUnconvertedNativeSum,
        BigDecimal currentValueUnconvertedNativeSum,
        BigDecimal unrealizedPnLUnconvertedNativeSum,
        BigDecimal unrealizedPnLPercent,
        BigDecimal realizedPnLUnconvertedNativeSum,
        BigDecimal totalDividendsUnconvertedNativeSum,
        BigDecimal totalReturnUnconvertedNativeSum,
        BigDecimal totalReturnPercent,
        /** Money-weighted annual return, in percent, over unconverted native amounts. */
        BigDecimal xirrPercent,
        /** BUY cost plus commission, per transaction currency. */
        Map<String, BigDecimal> totalInvestedByCurrency,
        /** Open positions at the latest price, per quote currency (may be GBp). */
        Map<String, BigDecimal> currentValueByCurrency,
        /** Open positions at average cost, per book currency. Unrealized = converted value − this. */
        Map<String, BigDecimal> openCostBasisByCurrency,
        /** FIFO realized profit, per SELL currency. */
        Map<String, BigDecimal> realizedPnLByCurrency,
        /** DIVIDEND transactions, per transaction currency. */
        Map<String, BigDecimal> totalDividendsByCurrency,
        List<Point> timeSeries) {

    public record Point(LocalDate date, Map<String, BigDecimal> valueByCurrency) {
    }
}
