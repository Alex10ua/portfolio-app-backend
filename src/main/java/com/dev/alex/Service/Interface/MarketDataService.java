package com.dev.alex.Service.Interface;

import com.dev.alex.Model.MarketData;

import java.math.BigDecimal;

public interface MarketDataService {

    MarketData getMarketDataByTicker(String ticker);
    void updatePriceByTicker(String ticker, BigDecimal price);
    MarketData getMarketDataForHoldingsPage(String ticker);
    void saveMarketData(MarketData marketData);
}
