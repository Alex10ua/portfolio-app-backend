package com.dev.alex.Model.NonDbModel.Ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Portfolio breakdown by classification. Unlike the SPA-facing
 * DiversificationCompleteData, every bucket keeps its amounts split per
 * currency, so a caller can convert them instead of adding unlike units.
 * Dimensions with nothing to report are empty lists, never null.
 */
public record AiDiversification(
        List<Bucket> byCountry,
        List<Bucket> bySector,
        List<Bucket> byIndustry,
        List<Bucket> byTicker,
        /** Positions with no sector/country on their market-data doc. */
        List<String> unclassifiedTickers) {

    public record Bucket(
            String name,
            Map<String, BigDecimal> amountsByCurrency,
            List<String> tickers) {
    }
}
