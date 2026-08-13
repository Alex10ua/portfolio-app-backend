package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One watchlist row: the user's target plus everything the Watchlist and Yield
 * Target tabs need, all in the ticker's own quote currency (see the currency rule
 * in CLAUDE.md — the API never converts).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class WatchlistEntry {
    private String ticker;
    private String name;
    /** Quote currency of price/dividend — may be 'GBp' (pence) while the book is GBP. */
    private String currency;
    private String sector;

    private BigDecimal price;
    private BigDecimal priceYesterday;
    /** Day move in percent; null when there is no previous close to compare against. */
    private BigDecimal dayChangePercent;

    /** Forward annual dividend per share. */
    private BigDecimal forwardDividend;
    /** Monthly | Quarterly | Semi-annual | Annual | Irregular; null when nothing is paid. */
    private String dividendFrequency;
    /** forwardDividend / price, in percent. */
    private BigDecimal forwardYield;

    private BigDecimal targetYield;
    /** Price at which forwardYield would equal targetYield. */
    private BigDecimal buyBelowPrice;
    /** Percent move from price to buyBelowPrice — negative means the price must fall. */
    private BigDecimal toTargetPercent;
    private boolean atTarget;

    private LocalDate exDividendDate;
    private LocalDate addedAt;
    private LocalDate priceUpdatedAt;
    /** True when this portfolio also holds the ticker — watched and owned are not exclusive. */
    private boolean held;

    /**
     * Monthly trailing-twelve-month yield, oldest first, at most the last 240
     * months. Empty when the ticker has no monthly price history or has never
     * paid a dividend; the client's percentile math then has nothing to rank.
     */
    private List<YieldPoint> yieldHistory;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class YieldPoint {
        /** 'YYYY-MM'. */
        private String month;
        /** Trailing-twelve-month dividend over that month's close, in percent. */
        private BigDecimal yield;
    }
}
