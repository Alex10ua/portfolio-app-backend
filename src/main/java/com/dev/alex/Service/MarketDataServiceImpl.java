package com.dev.alex.Service;

import com.dev.alex.Model.MarketData;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Service.Interface.MarketDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@Transactional
public class MarketDataServiceImpl implements MarketDataService {
    @Autowired
    private MarketDataRepository marketDataRepository;

    @Override
    public MarketData getMarketDataByTicker(String ticker) {
        return marketDataRepository.findByTicker(ticker);
    }

    @Override
    public void updatePriceByTicker(String ticker, BigDecimal price) {
        marketDataRepository.updatePriceByTicker(ticker, price);
    }

    @Description("get info for holding on Holding web-page")
    @Override
    public MarketData getMarketDataForHoldingsPage(String ticker) {
        return marketDataRepository.findByTickerForHoldingsPage(ticker);
    }

    @Description("batch fetch holdings-page market data for many tickers, keyed by ticker")
    @Override
    public java.util.Map<String, MarketData> getMarketDataForHoldingsPage(java.util.Collection<String> tickers) {
        java.util.Map<String, MarketData> byTicker = new java.util.HashMap<>();
        if (tickers == null || tickers.isEmpty()) return byTicker;
        for (MarketData md : marketDataRepository.findByTickerInForHoldingsPage(tickers)) {
            if (md.getTicker() != null) byTicker.put(md.getTicker(), md);
        }
        return byTicker;
    }

    @Override
    public void saveMarketData(MarketData marketData) {
        marketDataRepository.save(marketData);
    }
}
