package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PerformancePoint {
    private LocalDate date;
    /**
     * Sum of the native amounts below, unconverted — only meaningful for a
     * single-currency portfolio. Kept for older clients; new code reads
     * valueByCurrency and converts with the FX rates it holds.
     */
    private BigDecimal portfolioValue;
    /** native currency code (as quoted, e.g. "GBp") → value in that currency */
    private Map<String, BigDecimal> valueByCurrency;
    /** Held tickers valued with a legacy dividend-adjusted close because rawPrice was absent. */
    private List<String> estimatedPriceTickers;

    public PerformancePoint(LocalDate date, BigDecimal portfolioValue, Map<String, BigDecimal> valueByCurrency) {
        this(date, portfolioValue, valueByCurrency, List.of());
    }

    public PerformancePoint(LocalDate date, BigDecimal portfolioValue) {
        this(date, portfolioValue, null);
    }
}
