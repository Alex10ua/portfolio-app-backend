package com.dev.alex.Service.Interface;

import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.MarketStatistics;
import com.dev.alex.Model.NonDbModel.TickerHistoricalData;

import java.math.BigDecimal;

public interface MarketDataService {

    MarketData getMarketDataByTicker(String ticker);
    void updatePriceByTicker(String ticker, BigDecimal price);
    MarketData getMarketDataForHoldingsPage(String ticker);
    java.util.Map<String, MarketData> getMarketDataForHoldingsPage(java.util.Collection<String> tickers);
    void saveMarketData(MarketData marketData);

    /** Yahoo key statistics for the ticker; null when never fetched. */
    MarketStatistics getStatistics(String ticker);

    /** Dividends + splits + share-count series for the Historical page; null when the ticker is unknown. */
    TickerHistoricalData getHistoricalData(String ticker);
}
