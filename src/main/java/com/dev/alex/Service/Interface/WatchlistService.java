package com.dev.alex.Service.Interface;

import com.dev.alex.Model.NonDbModel.TickerSuggestion;
import com.dev.alex.Model.NonDbModel.WatchlistEntry;

import java.math.BigDecimal;
import java.util.List;

public interface WatchlistService {
    List<WatchlistEntry> getWatchlist(String portfolioId);

    /** targetYield null → defaults to the ticker's 5-year 90th-percentile yield. */
    WatchlistEntry addTicker(String portfolioId, String ticker, BigDecimal targetYield);

    WatchlistEntry setTargetYield(String portfolioId, String ticker, BigDecimal targetYield);

    void removeTicker(String portfolioId, String ticker);

    /** Pull fresh price/dividends/history from the market-data service, then re-read. */
    WatchlistEntry refreshTicker(String portfolioId, String ticker);

    List<TickerSuggestion> search(String portfolioId, String query);

    /**
     * What a row for this ticker would look like, built from stored data only —
     * no provider call, so unknown tickers come back null. Lets the add form show
     * the yield distribution the suggested target is drawn from.
     */
    WatchlistEntry preview(String portfolioId, String ticker);
}
