package com.dev.alex.Controller.Ai;

import com.dev.alex.Model.CompanyFundamentals;
import com.dev.alex.Model.NonDbModel.Ai.AiEnvelope;
import com.dev.alex.Model.NonDbModel.Ai.AiTickerData;
import com.dev.alex.Model.NonDbModel.Ai.AiTickerHistorical;
import com.dev.alex.Service.Ai.AiEnvelopeService;
import com.dev.alex.Service.Ai.AiTickerAssembler;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticker-level research. These are global market facts, not per-user data, so
 * they carry no portfolio scope and no ownership check — the same rule the
 * existing MarketDataController and FundamentalsController follow. A session is
 * still required.
 * <p>
 * Routed under a literal {@code /ticker} segment so a ticker symbol can never be
 * mistaken for a portfolio id.
 */
@RestController
@RequestMapping("/api/v1/ai/ticker")
public class AiTickerController {

    @Autowired
    private AiTickerAssembler assembler;
    @Autowired
    private AiEnvelopeService envelopeService;

    @Operation(summary = "Ticker market data and key statistics",
            description = "Price, classification, share count and the full Yahoo key-statistics snapshot "
                    + "for one symbol. 404 when no provider has ever fetched it.")
    @GetMapping("/{ticker}")
    public ResponseEntity<AiEnvelope<AiTickerData>> ticker(@PathVariable String ticker) {
        AiTickerData data = assembler.ticker(normalize(ticker));
        if (data == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(envelopeService.wrap(data, data.currency(),
                AiEnvelopeService.NOTE_STATISTICS_SCALING));
    }

    @Operation(summary = "Ticker dividend, split and share-count history",
            description = "Full event history plus derived dividend growth: per-year totals, year-over-year "
                    + "change, growth streak, last raise and the cumulative split factor.")
    @GetMapping("/{ticker}/historical")
    public ResponseEntity<AiEnvelope<AiTickerHistorical>> historical(
            @PathVariable String ticker,
            @RequestParam(required = false) Integer years) {
        AiTickerHistorical data = assembler.historical(normalize(ticker), years);
        if (data == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(envelopeService.wrap(data, data.currency(),
                AiEnvelopeService.NOTE_SPARSE_SHARES,
                "A year flagged partial is in progress or short of the usual payment cadence and is left "
                        + "out of the growth figures."));
    }

    @Operation(summary = "SEC EDGAR fundamentals",
            description = "Reported financial concepts (revenue, net income, assets, EPS, ...) newest first. "
                    + "US SEC registrants only — 404 for foreign issuers and crypto.")
    @GetMapping("/{ticker}/fundamentals")
    public ResponseEntity<AiEnvelope<CompanyFundamentals>> fundamentals(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "12") int limit) {
        String symbol = normalize(ticker);
        CompanyFundamentals data = assembler.fundamentals(symbol, limit);
        if (data == null) return ResponseEntity.notFound().build();
        AiTickerData market = assembler.ticker(symbol);
        return ResponseEntity.ok(envelopeService.wrap(data,
                market == null ? null : market.currency(),
                "Values are as reported to the SEC, in the filing's own currency (US filers report USD). "
                        + "Each entry names the form it came from; a concept the company never reported is "
                        + "absent rather than zero."));
    }

    private static String normalize(String ticker) {
        return ticker == null ? null : ticker.trim().toUpperCase();
    }
}
