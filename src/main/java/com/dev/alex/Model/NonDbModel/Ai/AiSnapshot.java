package com.dev.alex.Model.NonDbModel.Ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Complete state of one portfolio in a single response: positions, both kinds
 * of cash, realized P&amp;L, and value totals already grouped by currency so a
 * caller converts once per currency rather than once per position.
 */
public record AiSnapshot(
        String portfolioId,
        String portfolioName,
        LocalDate asOf,
        List<AiPosition> positions,
        List<CurrencyTotal> totalsByCurrency,
        /** User-entered cash positions (cashHoldings collection). */
        List<CashEntry> manualCash,
        /**
         * Cash derived from transaction flows. NOTE: the underlying endpoint
         * currently sums DEPOSIT and WITHDRAWAL only — BUY/SELL/DIVIDEND/TAX
         * are not yet included (TODO.list A1).
         */
        Map<String, BigDecimal> derivedCashBalance,
        /** FIFO realized P&amp;L per currency, from fully or partly sold positions. */
        Map<String, BigDecimal> realizedPnLByCurrency,
        int positionCount,
        /** Positions whose price has not been refreshed in over 4 days. */
        int stalePriceCount) {

    /** Value/cost totals for every position booked in one currency. */
    public record CurrencyTotal(
            String currency,
            BigDecimal marketValue,
            BigDecimal costBasis,
            BigDecimal unrealizedProfit,
            BigDecimal unrealizedProfitPercent,
            int positionCount) {
    }

    public record CashEntry(String currency, BigDecimal amount, LocalDate updatedAt) {
    }
}
