package com.dev.alex.Service.Interface;

import java.util.List;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Model.Enums.Assets;

public interface HoldingsService {

    List<Holdings> getAllHoldingsByPortfolioId(String portfolioId);
    Holdings findHoldingByPortfolioIdAndTicker(String portfolioId, String tickerSymbol);
    void updateOrCreateHoldingInPortfolioUpdated(String portfolioId, Transactions newTransaction);
    void recalculateOrCreateHoldingFromTicker(String portfolioId, String ticker, Assets assetType);
    void recalculateOrCreateCustomHoldingFromTicker(String portfolioId, String ticker, Assets assetType);
    void updateOrCreateCustomHoldingInPortfolio(String portfolioId, Transactions newTransaction);
    void recalculateHoldingFromTransactions(String portfolioId, String ticker);

}
