package com.dev.alex.Controller.Ai;

import com.dev.alex.Model.NonDbModel.Ai.AiDividendCalendar;
import com.dev.alex.Model.NonDbModel.Ai.AiDividends;
import com.dev.alex.Model.NonDbModel.Ai.AiEnvelope;
import com.dev.alex.Model.NonDbModel.Ai.AiWatchlistRow;
import com.dev.alex.Service.Ai.AiEnvelopeService;
import com.dev.alex.Service.Ai.AiIncomeAssembler;
import com.dev.alex.Service.PortfolioAccessService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dividend income and watchlist endpoints — what the portfolio pays out, when,
 * and which watched tickers are cheap by their own historical yield.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiIncomeController {

    @Autowired
    private AiIncomeAssembler assembler;
    @Autowired
    private AiEnvelopeService envelopeService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @Operation(summary = "Dividend income",
            description = "Dividends actually received, per ticker and as monthly, quarterly and yearly "
                    + "series, plus the forward twelve-month projection. Every series stays inside one "
                    + "currency.")
    @GetMapping("/{portfolioId}/dividends")
    public AiEnvelope<AiDividends> dividends(@PathVariable String portfolioId, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        AiDividends payload = assembler.dividends(portfolioId);
        return envelopeService.wrap(payload,
                envelopeService.baseCurrency(username, portfolioId, payload.monthlyByCurrency().keySet()));
    }

    @Operation(summary = "Dividend calendar",
            description = "Expected income by calendar month at current holding sizes, with each amount's "
                    + "currency and the per-position total already multiplied out.")
    @GetMapping("/{portfolioId}/dividend-calendar")
    public AiEnvelope<AiDividendCalendar> dividendCalendar(
            @PathVariable String portfolioId, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        AiDividendCalendar payload = assembler.dividendCalendar(portfolioId);
        return envelopeService.wrap(payload,
                envelopeService.baseCurrency(username, portfolioId, payload.annualTotalsByCurrency().keySet()),
                "Months are a repeating annual schedule inferred from each ticker's payment history, not "
                        + "dated events for a specific year.");
    }

    @Operation(summary = "Watchlist with yield ranking",
            description = "Watched tickers with target yield, buy-below price, and where today's yield sits "
                    + "in that ticker's own distribution over 1Y/3Y/5Y/10Y/All windows.")
    @GetMapping("/{portfolioId}/watchlist")
    public AiEnvelope<List<AiWatchlistRow>> watchlist(
            @PathVariable String portfolioId,
            @RequestParam(defaultValue = "60") int historyMonths,
            Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        List<AiWatchlistRow> rows = assembler.watchlist(portfolioId, Math.min(Math.max(historyMonths, 0), 240));
        List<String> currencies = rows.stream()
                .map(r -> r.item().getCurrency())
                .filter(java.util.Objects::nonNull)
                .toList();
        return envelopeService.wrap(rows,
                envelopeService.baseCurrency(username, portfolioId, currencies),
                "Yields are percentages and need no conversion. yieldHistory is trailing-twelve-month "
                        + "yield per month, oldest first, trimmed to historyMonths — the percentile stats "
                        + "are computed over the full series regardless.");
    }
}
