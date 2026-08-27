package com.dev.alex.Model.NonDbModel.Ai;

import java.util.List;

/**
 * One row of GET /api/v1/ai/portfolios — enough to pick a portfolio and know
 * what currencies its figures will arrive in, without pulling every position.
 */
public record AiPortfolioSummary(
        String portfolioId,
        String portfolioName,
        String description,
        Integer firstTradeYear,
        int positionCount,
        /** Distinct book currencies present in the holdings, sorted. */
        List<String> currencies,
        /** User's chosen display currency, or the auto-detected fallback. */
        String baseCurrency) {
}
