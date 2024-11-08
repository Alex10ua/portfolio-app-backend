package com.dev.alex.Service.Interface;

import com.dev.alex.Model.MarketData;

public interface MarketDataService {

    public MarketData getMarketDataByTicker(String ticker);
}
