package com.dev.alex.Controller.Ai;

import com.dev.alex.Model.NonDbModel.Ai.AiConventions;
import com.dev.alex.Model.NonDbModel.Ai.AiEnvelope;
import com.dev.alex.Service.Ai.AiEnvelopeService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference data that belongs to no single portfolio: the FX table and the
 * written data contract every other AI endpoint assumes.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiReferenceController {

    @Autowired
    private AiEnvelopeService envelopeService;

    @Operation(summary = "Exchange rates",
            description = "Currency code to units per 1 EUR, including synthetic GBp/GBx entries "
                    + "(GBP x 100) so pence quotes convert with the same formula as everything else.")
    @GetMapping("/fx-rates")
    public AiEnvelope<Map<String, BigDecimal>> fxRates() {
        Map<String, BigDecimal> rates = envelopeService.fxRates();
        return envelopeService.wrap(rates, "EUR",
                "Rates are quoted against the euro, so EUR is always 1.0. A currency missing from this "
                        + "map has no published rate — say so rather than assuming parity.");
    }

    @Operation(summary = "Data conventions",
            description = "How to read every AI response: currency handling, what null means, what each "
                    + "asset and transaction type does, and the known gaps in the data.")
    @GetMapping("/conventions")
    public AiEnvelope<AiConventions> conventions() {
        Map<String, String> assetTypes = new LinkedHashMap<>();
        assetTypes.put("STOCK", "Listed equity or ETF; priced by Yahoo, carries sector/country/statistics.");
        assetTypes.put("CRYPTO", "Cryptocurrency; priced by CoinGecko. sharesOutstanding is circulating supply.");
        assetTypes.put("CUSTOM", "User-defined asset with a user-set price (bullion, collectibles, pension funds). "
                + "No market data, no sector.");
        assetTypes.put("COIN", "Legacy custom type, superseded by CUSTOM. Still present on older rows.");
        assetTypes.put("FIGURINE", "Legacy custom type, superseded by CUSTOM.");
        assetTypes.put("FUND", "Legacy custom type, superseded by CUSTOM.");

        Map<String, String> transactionTypes = new LinkedHashMap<>();
        transactionTypes.put("BUY", "Increases share count and cost basis.");
        transactionTypes.put("SELL", "Decreases share count; realizes P&L on a FIFO basis.");
        transactionTypes.put("DIVIDEND", "Cash received. Does not change share count.");
        transactionTypes.put("TAX", "Cash paid, usually withholding on a dividend. Does not change share count.");
        transactionTypes.put("DEPOSIT", "Cash added to the account. Not a holding.");
        transactionTypes.put("WITHDRAWAL", "Cash removed from the account. Not a holding.");

        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("GET /api/v1/ai/portfolios", "Which portfolios exist and what currencies they use.");
        endpoints.put("GET /api/v1/ai/{portfolioId}/snapshot", "Whole portfolio: positions, cash, totals, realized P&L.");
        endpoints.put("GET /api/v1/ai/{portfolioId}/positions", "Positions only.");
        endpoints.put("GET /api/v1/ai/{portfolioId}/allocation-targets", "Target weight per ticker vs actual weight, drift and the trade that closes it.");
        endpoints.put("GET /api/v1/ai/{portfolioId}/diversification", "Value by country, sector, industry, ticker.");
        endpoints.put("GET /api/v1/ai/{portfolioId}/tags", "Positions grouped by user tag.");
        endpoints.put("GET /api/v1/ai/{portfolioId}/transactions", "Filtered, paged transaction history.");
        endpoints.put("GET /api/v1/ai/{portfolioId}/performance", "Invested, value, P&L, XIRR and value series.");
        endpoints.put("GET /api/v1/ai/{portfolioId}/history", "Month-end value series per currency.");
        endpoints.put("GET /api/v1/ai/{portfolioId}/realized-pnl", "FIFO realized P&L per currency.");
        endpoints.put("GET /api/v1/ai/{portfolioId}/dividends", "Dividends received, monthly/quarterly/yearly.");
        endpoints.put("GET /api/v1/ai/{portfolioId}/dividend-calendar", "Expected income by calendar month.");
        endpoints.put("GET /api/v1/ai/{portfolioId}/watchlist", "Watched tickers with yield percentile ranking.");
        endpoints.put("GET /api/v1/ai/ticker/{ticker}", "Market data and key statistics for one symbol.");
        endpoints.put("GET /api/v1/ai/ticker/{ticker}/historical", "Dividend, split and share-count history.");
        endpoints.put("GET /api/v1/ai/ticker/{ticker}/fundamentals", "SEC EDGAR reported financials.");
        endpoints.put("GET /api/v1/ai/fx-rates", "Currency to units per 1 EUR.");

        AiConventions conventions = new AiConventions(
                List.of(
                        AiEnvelopeService.NOTE_NATIVE_CURRENCY,
                        AiEnvelopeService.NOTE_PENCE,
                        "A position's currency is its book currency; quoteCurrency is what the provider "
                                + "quotes its price in. They differ for pence-quoted London listings.",
                        AiEnvelopeService.CONVERSION_FORMULA),
                List.of(
                        AiEnvelopeService.NOTE_NULLS,
                        AiEnvelopeService.NOTE_STATISTICS_SCALING,
                        AiEnvelopeService.NOTE_UNCONVERTED_SCALARS,
                        AiEnvelopeService.NOTE_ALLOCATION_TARGETS,
                        AiEnvelopeService.NOTE_SPARSE_SHARES,
                        "daysStale counts days since the price was last refreshed. Over "
                                + AiEnvelopeService.STALE_DAYS + " days, say the figure may be out of date."),
                assetTypes,
                transactionTypes,
                List.of(
                        AiEnvelopeService.NOTE_CASH_BALANCE,
                        "Custom assets are priced by the user, so their value is an estimate, not a quote.",
                        "The dividend calendar is a repeating annual schedule inferred from payment history, "
                                + "not dated events for a specific year.",
                        "Everything here is read-only. There is no endpoint to record a trade or change a "
                                + "holding, so never claim an action was taken."),
                endpoints);

        return envelopeService.wrap(conventions, "EUR");
    }
}
