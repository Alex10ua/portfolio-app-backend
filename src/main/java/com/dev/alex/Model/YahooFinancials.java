package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Yahoo Finance financial statements, one doc per equity (_id = ticker), written by
 * Flask (refresh_yahoo_financials): on the regular Yahoo update once the stored copy
 * is YF_FINANCIALS_MAX_AGE_DAYS old, or on demand via /update/financials.
 * <p>
 * Each statement maps Yahoo's line-item key (TotalRevenue, GrossProfit,
 * OperatingIncome, DilutedEPS, DilutedAverageShares, FreeCashFlow, ...) to its
 * date-ascending series. Every line item Yahoo published is kept; one it did not
 * report is absent, never zero. Covers non-US issuers too, unlike
 * {@link CompanyFundamentals} (SEC EDGAR, US filers only).
 * <p>
 * All amounts are in {@link #currency}, the company's <b>reporting</b> currency,
 * which is not the quote currency: ULVR.L is quoted in GBp and reports in EUR.
 */
@Document(collection = "yahooFinancials")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class YahooFinancials {
    @Id
    private String ticker;
    /** Yahoo's financialCurrency; null when Yahoo did not say. */
    private String currency;
    /** Last 4-5 fiscal years. */
    private Statements annual;
    /** Trailing twelve months — one period per item, and Yahoo leaves gaps (e.g. no TTM DilutedEPS for MSFT). */
    private Statements trailing;
    /** When statements were last written. */
    private Date updatedAt;
    /** When Yahoo was last asked, data or not — the staleness gate reads this. */
    private Date checkedAt;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Statements {
        private Map<String, List<StatementEntry>> income;
        /** Annual only: Yahoo has no trailing balance sheet. */
        private Map<String, List<StatementEntry>> balanceSheet;
        private Map<String, List<StatementEntry>> cashFlow;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StatementEntry {
        private String date;   // 'YYYY-MM-DD', the period end
        private BigDecimal value;
    }
}
