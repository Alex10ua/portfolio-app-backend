package com.dev.alex.Service.Interface;

import java.util.List;
import com.dev.alex.Model.Holdings;

public interface HoldingsService {

    List<Holdings> getAllHoldingsByPortfolioId(String portfolioId);
    void updateHoldingsByHoldingId(String holdingId);
    void updateHoldingByPortfolioIdAndTickerSymbol(String portfolioId, String tickerSymbol, Holdings holding);
    Holdings findHoldingByPortfolioIdAndTicker(String portfolioId, String tickerSymbol);


}
