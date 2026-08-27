package com.dev.alex.Controller.Ai;

import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.NonDbModel.Ai.AiDiversification;
import com.dev.alex.Model.NonDbModel.Ai.AiEnvelope;
import com.dev.alex.Model.NonDbModel.Ai.AiPortfolioSummary;
import com.dev.alex.Model.NonDbModel.Ai.AiPosition;
import com.dev.alex.Model.NonDbModel.Ai.AiSnapshot;
import com.dev.alex.Model.NonDbModel.Ai.AiTagGroup;
import com.dev.alex.Model.NonDbModel.Ai.AiTransactionsPage;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Service.Ai.AiEnvelopeService;
import com.dev.alex.Service.Ai.AiPortfolioAssembler;
import com.dev.alex.Service.FxRateServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.TransactionServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * Portfolio-level AI endpoints: what is held, what it cost, what it is worth.
 * <p>
 * Read-only by construction — this package declares no write mapping. Every
 * portfolio-scoped method asserts ownership, exactly like the SPA controllers.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiPortfolioController {

    @Autowired
    private AiPortfolioAssembler assembler;
    @Autowired
    private AiEnvelopeService envelopeService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;
    @Autowired
    private TransactionServiceImpl transactionService;
    @Autowired
    private FxRateServiceImpl fxRateService;

    @Operation(summary = "List portfolios",
            description = "Every portfolio the caller owns, with position count, the currencies its "
                    + "figures arrive in, and the display currency the user reads totals in.")
    @GetMapping("/portfolios")
    public AiEnvelope<List<AiPortfolioSummary>> portfolios(Authentication authentication) {
        List<AiPortfolioSummary> summaries = assembler.summaries(authentication.getName());
        String base = summaries.size() == 1 ? summaries.get(0).baseCurrency() : null;
        return envelopeService.wrap(summaries, base);
    }

    @Operation(summary = "Complete portfolio snapshot",
            description = "Whole state of one portfolio in one call: positions, cash, realized P&L and "
                    + "value totals already grouped by currency.")
    @GetMapping("/{portfolioId}/snapshot")
    public AiEnvelope<AiSnapshot> snapshot(@PathVariable String portfolioId, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        AiSnapshot snapshot = assembler.snapshot(portfolioId, username);
        return envelopeService.wrap(snapshot,
                baseCurrency(username, portfolioId, snapshot.positions()),
                AiEnvelopeService.NOTE_CASH_BALANCE);
    }

    @Operation(summary = "Positions only",
            description = "The holdings of one portfolio without cash or P&L — the lighter half of the snapshot.")
    @GetMapping("/{portfolioId}/positions")
    public AiEnvelope<List<AiPosition>> positions(@PathVariable String portfolioId, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        List<AiPosition> positions = assembler.positions(portfolioId, username);
        return envelopeService.wrap(positions, baseCurrency(username, portfolioId, positions));
    }

    @Operation(summary = "Portfolio breakdown",
            description = "Value by country, sector, industry and ticker, each bucket split per currency.")
    @GetMapping("/{portfolioId}/diversification")
    public AiEnvelope<AiDiversification> diversification(@PathVariable String portfolioId, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        List<AiPosition> positions = assembler.positions(portfolioId, username);
        return envelopeService.wrap(assembler.diversification(positions),
                baseCurrency(username, portfolioId, positions));
    }

    @Operation(summary = "Holdings grouped by tag",
            description = "User tags with the positions carrying them and their value per currency.")
    @GetMapping("/{portfolioId}/tags")
    public AiEnvelope<List<AiTagGroup>> tags(@PathVariable String portfolioId, Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);
        List<AiPosition> positions = assembler.positions(portfolioId, username);
        return envelopeService.wrap(assembler.tagGroups(positions),
                baseCurrency(username, portfolioId, positions));
    }

    @Operation(summary = "Transactions",
            description = "Filtered, paged transaction history, newest first. Each row carries the FX rate "
                    + "for its currency. DEPOSIT/WITHDRAWAL are cash events; DIVIDEND/TAX are cash events "
                    + "that do not change share counts.")
    @GetMapping("/{portfolioId}/transactions")
    public AiEnvelope<AiTransactionsPage> transactions(
            @PathVariable String portfolioId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String ticker,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "200") int limit,
            Authentication authentication) {
        String username = authentication.getName();
        portfolioAccessService.assertOwnership(portfolioId, username);

        TransactionType wanted = parseType(type);
        String wantedTicker = ticker == null || ticker.isBlank() ? null : ticker.trim().toUpperCase();

        List<Transactions> matching = transactionService.findAllByPortfolioId(portfolioId).stream()
                .filter(t -> year == null || (t.getDate() != null && t.getDate().getYear() == year))
                .filter(t -> wantedTicker == null || wantedTicker.equals(t.getTicker()))
                .filter(t -> wanted == null || wanted == t.getTransactionType())
                .sorted(Comparator.comparing(Transactions::getDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int safeOffset = Math.max(0, offset);
        int safeLimit = limit <= 0 ? 200 : Math.min(limit, 1000);
        int from = Math.min(safeOffset, matching.size());
        int to = Math.min(from + safeLimit, matching.size());
        List<Transactions> page = matching.subList(from, to);
        page.forEach(t -> t.setFxRate(fxRateService.getRateForCurrency(t.getCurrency())));

        AiTransactionsPage payload = new AiTransactionsPage(matching.size(), safeOffset, safeLimit, page);
        return envelopeService.wrap(payload, envelopeService.baseCurrency(username, portfolioId, null));
    }

    private static TransactionType parseType(String type) {
        if (type == null || type.isBlank()) return null;
        try {
            return TransactionType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown transaction type: " + type
                    + ". Expected one of BUY, SELL, DIVIDEND, TAX, DEPOSIT, WITHDRAWAL.");
        }
    }

    private String baseCurrency(String username, String portfolioId, List<AiPosition> positions) {
        return envelopeService.baseCurrency(username, portfolioId,
                positions.stream().map(AiPosition::currency).toList());
    }
}
