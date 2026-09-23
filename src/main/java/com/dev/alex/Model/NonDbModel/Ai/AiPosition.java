package com.dev.alex.Model.NonDbModel.Ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One holding, enriched with everything an AI needs to reason about it without
 * a second call: classification, tags, allocation target and price freshness.
 * <p>
 * Two currencies are exposed on purpose. {@code currency} is the book currency
 * the position was transacted in (e.g. GBP, or EUR for a coin bought in euros);
 * {@code quoteCurrency} is what the provider quotes price and dividends in and may
 * be {@code GBp} (pence) or USD. They are not interchangeable — see CLAUDE.md.
 * Cost, market value and profit are in {@code currency}: when the quote differs,
 * the market value was converted from it at the envelope's fxRates. The per-share
 * market figures (price, dividendPerShare) and dividendsReceived stay in
 * {@code quoteCurrency}.
 */
public record AiPosition(
        String ticker,
        String name,
        /** STOCK | CRYPTO | CUSTOM | COIN | FIGURINE | FUND (last three legacy). */
        String assetType,
        BigDecimal shares,
        /** In currency. */
        BigDecimal costPerShare,
        /** In currency. */
        BigDecimal costBasis,
        /** Latest quote, in quoteCurrency. */
        BigDecimal price,
        /** shares × price, converted into currency when quoteCurrency differs. */
        BigDecimal marketValue,
        /** marketValue − costBasis, in currency. */
        BigDecimal unrealizedProfit,
        BigDecimal unrealizedProfitPercent,
        /** Price move since the previous close, in percent. */
        BigDecimal dayChangePercent,
        /** Annual dividend per share, in quoteCurrency. */
        BigDecimal dividendPerShare,
        BigDecimal dividendYieldPercent,
        BigDecimal dividendYieldOnCostPercent,
        /** Dividends this position has paid the portfolio (stocks only), in quoteCurrency. */
        BigDecimal dividendsReceived,
        /** Book currency of costBasis/marketValue/unrealizedProfit. */
        String currency,
        /** Provider quote currency of price/dividends; may be "GBp". */
        String quoteCurrency,
        /**
         * Share of the market value of all positions that share this currency,
         * in percent. Needs no FX; cross-currency weight is the caller's job.
         */
        BigDecimal percentOfCurrencyBucket,
        /** Desired weight of the whole portfolio, in percent; null when unset. */
        Double targetPercent,
        Long sharesOutstanding,
        String sector,
        String country,
        String industry,
        LocalDate priceUpdatedAt,
        /** Days since the price was last refreshed; null when never updated. */
        Integer daysStale,
        List<String> tags) {
}
