package com.dev.alex.Service.Interface;

import com.dev.alex.Model.MarketData;

import java.math.BigDecimal;

public interface MarketDataService {

    public MarketData getMarketDataByTicker(String ticker);
    void updatePriceByTicker(String ticker, BigDecimal price);
    public MarketData getMarketDataForHoldingsPage(String ticker);
}
