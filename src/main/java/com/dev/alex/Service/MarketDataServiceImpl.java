package com.dev.alex.Service;

import com.dev.alex.Model.MarketData;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Service.Interface.MarketDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MarketDataServiceImpl implements MarketDataService {
    @Autowired
    private MarketDataRepository marketDataRepository;

    @Override
    public MarketData getMarketDataByTicker(String ticker) {
        return marketDataRepository.findByTicker(ticker);
    }
}
