package com.dev.alex.Model.NonDbModel.Ai;

import com.dev.alex.Model.NonDbModel.MarketStatistics;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything known about one ticker, read from the full marketData document.
 * <p>
 * Deliberately not built on GET /api/v1/market-data/{ticker}: that endpoint is
 * a Mongo projection which returns null for currency, sector, country,
 * industry, statistics and updatedAt.
 */
public record AiTickerData(
        String ticker,
        String name,
        /** Quote currency of every money figure below; may be "GBp" (pence). */
        String currency,
        BigDecimal price,
        BigDecimal priceYesterday,
        BigDecimal dayChangePercent,
        BigDecimal lastDividendPayment,
        /** Forward annual dividend per share. */
        BigDecimal yearlyDividend,
        String sector,
        String country,
        String industry,
        Long sharesOutstanding,
        LocalDate priceUpdatedAt,
        Integer daysStale,
        /** Yahoo key statistics; null until a Yahoo update has run for this ticker. */
        MarketStatistics statistics,
        int dividendEventCount,
        int splitEventCount) {
}
