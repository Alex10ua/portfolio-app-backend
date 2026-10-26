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
        /** Remaining FIFO cost including BUY fees, per book currency. Unrealized = converted value − this. */
        Map<String, BigDecimal> openCostBasisByCurrency,
        /** FIFO proceeds and consumed purchase cost, in their original currencies. */
        Map<String, BigDecimal> realizedPnLByCurrency,
        /** All BUY/SELL fees, in their original currencies. */
        Map<String, BigDecimal> commissionsByCurrency,
        /** Fees allocated to sold quantities; already included in realized P&L. */
        Map<String, BigDecimal> realizedCommissionsByCurrency,
        /** DIVIDEND transactions, per transaction currency. */
        Map<String, BigDecimal> totalDividendsByCurrency,
        List<Point> timeSeries) {

    public record Point(LocalDate date, Map<String, BigDecimal> valueByCurrency,
                        List<String> estimatedPriceTickers) {
    }
}
