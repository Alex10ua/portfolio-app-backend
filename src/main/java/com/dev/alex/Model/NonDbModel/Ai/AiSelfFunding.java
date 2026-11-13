package com.dev.alex.Model.NonDbModel.Ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** All monetary row values use that ticker's quote currency; no currencies are added together. */
public record AiSelfFunding(String period, LocalDate asOfDate, int excludedCount, List<Row> rows) {
    public record Row(String ticker, String name, String assetType, String currency,
            BigDecimal price, BigDecimal annualDividendPerShare, BigDecimal heldShares,
            Integer paymentsPerYear, List<String> tags, BigDecimal dividendPerPeriod,
            BigDecimal dividendPerPayment, BigDecimal neededShares, BigDecimal gapShares,
            BigDecimal gapCost, BigDecimal sharesPerPeriod, BigDecimal coveragePercent,
            BigDecimal yieldPercent, BigDecimal estimatedReinvestmentYears, String tier) {}
}
