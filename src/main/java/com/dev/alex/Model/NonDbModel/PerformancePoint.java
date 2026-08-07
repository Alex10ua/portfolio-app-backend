package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

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

    public PerformancePoint(LocalDate date, BigDecimal portfolioValue) {
        this(date, portfolioValue, null);
    }
}
