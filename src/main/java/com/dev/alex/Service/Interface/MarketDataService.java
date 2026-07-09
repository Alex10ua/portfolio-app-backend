package com.dev.alex.Service.Interface;

import com.dev.alex.Model.MarketData;

import java.math.BigDecimal;

public interface MarketDataService {

    MarketData getMarketDataByTicker(String ticker);
    void updatePriceByTicker(String ticker, BigDecimal price);
    MarketData getMarketDataForHoldingsPage(String ticker);
    java.util.Map<String, MarketData> getMarketDataForHoldingsPage(java.util.Collection<String> tickers);
    void saveMarketData(MarketData marketData);
}
