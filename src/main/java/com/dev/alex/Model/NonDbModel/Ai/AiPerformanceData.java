package com.dev.alex.Model.NonDbModel.Ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Portfolio performance. The scalar fields carry an explicit
 * {@code UnconvertedNativeSum} suffix because that is what they are: sums taken
 * across currencies without any FX applied, so they are exact only for a
 * single-currency portfolio. The time series is the trustworthy part — each
 * point breaks value down per currency and can be converted properly.
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
        /** Money-weighted annual return, in percent. */
        BigDecimal xirrPercent,
        List<Point> timeSeries) {

    public record Point(LocalDate date, Map<String, BigDecimal> valueByCurrency) {
    }
}
