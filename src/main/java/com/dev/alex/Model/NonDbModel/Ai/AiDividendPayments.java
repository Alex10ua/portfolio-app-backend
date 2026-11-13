package com.dev.alex.Model.NonDbModel.Ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Calendar tracking, with date-based assumptions kept separate from user confirmations. */
public record AiDividendPayments(int year, LocalDate asOfDate, String statusFilter,
        Map<String, Long> countsByStatus, List<Payment> payments) {
    public record Payment(String ticker, String name, String currency, String country,
            LocalDate exDate, LocalDate payDate, boolean payDateEstimated, boolean scheduled,
            BigDecimal dividendPerShare, BigDecimal shares, BigDecimal grossAmount,
            Integer paymentsPerYear, String naturalStatus, String status, String receiptStatusSource,
            String receiptOverrideStatus, LocalDate receiptOverrideExDate, boolean explicitlyConfirmedReceived,
            BigDecimal withholdingRatePercent, String withholdingSource,
            BigDecimal estimatedWithheldAmount, BigDecimal estimatedNetAmount) {}
}
