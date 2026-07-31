package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Yahoo Finance key statistics, embedded in the marketData document under
 * "statistics". Written wholesale by Flask (updateMarketDataUtilities.get_statistics
 * / STATISTICS_FIELDS) — field names here must match the output keys there.
 * <p>
 * Everything is nullable: a provider that doesn't report a metric leaves the key
 * out entirely rather than writing a zero, so null means "not reported", never 0.
 * Values are raw as Yahoo reports them; percentage formatting belongs in the UI.
 * Yahoo's scaling is inconsistent: margins/growth/returns are fractions
 * (0.3934 = 39.34%), while dividendYield, fiveYearAvgDividendYield and
 * debtToEquity are already percentages (0.93 = 0.93%, 30.27 = 30.27%).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MarketStatistics {

    // Fiscal year
    private LocalDate fiscalYearEnd;
    private LocalDate mostRecentQuarter;

    // Profitability
    private BigDecimal profitMargin;
    private BigDecimal operatingMargin;
    private BigDecimal grossMargin;
    private BigDecimal ebitdaMargin;

    // Management effectiveness
    private BigDecimal returnOnAssets;
    private BigDecimal returnOnEquity;

    // Income statement (ttm)
    private Long revenue;
    private BigDecimal revenuePerShare;
    private BigDecimal revenueGrowth;
    private Long grossProfit;
    private Long ebitda;
    private Long netIncomeToCommon;
    private BigDecimal dilutedEps;
    private BigDecimal forwardEps;
    private BigDecimal earningsQuarterlyGrowth;
    private BigDecimal earningsGrowth;

    // Balance sheet (mrq)
    private Long totalCash;
    private BigDecimal totalCashPerShare;
    private Long totalDebt;
    private BigDecimal debtToEquity;
    private BigDecimal currentRatio;
    private BigDecimal quickRatio;
    private BigDecimal bookValuePerShare;

    // Cash flow (ttm)
    private Long operatingCashflow;
    private Long freeCashflow;

    // Valuation measures
    private Long marketCap;
    private Long enterpriseValue;
    private BigDecimal trailingPE;
    private BigDecimal forwardPE;
    private BigDecimal pegRatio;
    private BigDecimal priceToSales;
    private BigDecimal priceToBook;
    private BigDecimal enterpriseToRevenue;
    private BigDecimal enterpriseToEbitda;

    // Trading / price stats
    private BigDecimal beta;
    private BigDecimal fiftyTwoWeekHigh;
    private BigDecimal fiftyTwoWeekLow;
    private BigDecimal fiftyTwoWeekChange;
    private BigDecimal sp500FiftyTwoWeekChange;
    private BigDecimal fiftyDayAverage;
    private BigDecimal twoHundredDayAverage;
    private Long volume;
    private Long averageVolume;
    private Long averageVolume10days;

    // Share statistics
    private Long sharesOutstanding;
    private Long impliedSharesOutstanding;
    private Long floatShares;
    private Long sharesShort;
    private Long sharesShortPriorMonth;
    private BigDecimal shortRatio;
    private BigDecimal shortPercentOfFloat;
    private BigDecimal heldPercentInsiders;
    private BigDecimal heldPercentInstitutions;

    // Dividends & splits
    private BigDecimal dividendRate;
    private BigDecimal dividendYield;
    private BigDecimal trailingAnnualDividendRate;
    private BigDecimal trailingAnnualDividendYield;
    private BigDecimal fiveYearAvgDividendYield;
    private BigDecimal payoutRatio;
    private LocalDate exDividendDate;
    private LocalDate nextDividendDate;
    private String lastSplitFactor;
    private LocalDate lastSplitDate;

    // Analyst view
    private BigDecimal targetHighPrice;
    private BigDecimal targetLowPrice;
    private BigDecimal targetMeanPrice;
    private BigDecimal recommendationMean;
    private String recommendationKey;
    private Integer numberOfAnalystOpinions;

    // Company profile
    private Integer fullTimeEmployees;
    private String exchange;
    private String quoteType;
    private String website;

    /** When Flask last wrote this snapshot (not a provider-reported figure). */
    private LocalDate updatedAt;

    /**
     * Currency the monetary figures are quoted in — may be "GBp" (pence). Not
     * written by the provider: filled from MarketData.currency when the document
     * is read, since Flask $sets this whole sub-document on every update and a
     * stored copy would be wiped.
     */
    private String currency;
}
