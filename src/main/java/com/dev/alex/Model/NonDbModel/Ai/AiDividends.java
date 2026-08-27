package com.dev.alex.Model.NonDbModel.Ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Dividend income, every series kept per currency. The flat cross-currency sums
 * the SPA endpoint also returns are deliberately dropped here — they add unlike
 * units and are exact only for a single-currency portfolio.
 * <p>
 * Year and quarter rollups are pure regrouping of the monthly series, no FX.
 */
public record AiDividends(
        List<TickerAmount> receivedByTicker,
        /** currency -&gt; ("yyyy-MM" -&gt; amount received) */
        Map<String, Map<String, BigDecimal>> monthlyByCurrency,
        /** currency -&gt; ("yyyy" -&gt; amount received) */
        Map<String, Map<String, BigDecimal>> yearlyByCurrency,
        /** currency -&gt; ("yyyy-Qn" -&gt; amount received) */
        Map<String, Map<String, BigDecimal>> quarterlyByCurrency,
        /** currency -&gt; forward next-12-months projection in that currency */
        Map<String, BigDecimal> forwardProjectionByCurrency) {

    public record TickerAmount(String ticker, BigDecimal amount, String currency) {
    }
}
