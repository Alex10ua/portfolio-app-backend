package com.dev.alex.Service;

import com.dev.alex.Model.Dividend;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.HoldingsCompleteData;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Service.Interface.HoldingsCompleteDataService;
import com.dev.alex.Service.Interface.HoldingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

@Service
public class HoldingsCompleteDataServiceImpl implements HoldingsCompleteDataService {
    @Autowired
    private HoldingsRepository holdingsRepository;
    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private MarketDataServiceImpl marketDataService;
    @Override
    public List<HoldingsCompleteData> getAllHoldingsByPortfolioId(String portfolioId) {

        List<HoldingsCompleteData> holdingsCompleteDataList = new ArrayList<>();
        List<Holdings> allHolding = holdingService.getAllHoldingsByPortfolioId(portfolioId);

        for (Holdings holding : allHolding){
            HoldingsCompleteData holdingsCompleteData = new HoldingsCompleteData();
            MarketData marketData = marketDataService.getMarketDataByTicker(holding.getTicker().toUpperCase());
            holdingsCompleteData.setName(marketData.getName());
            holdingsCompleteData.setTicker(holding.getTicker().toUpperCase());
            holdingsCompleteData.setShareAmount(holding.getQuantity());
            holdingsCompleteData.setCostPerShare(holding.getAveragePurchasePrice());
            holdingsCompleteData.setCostBasis(holding.getAveragePurchasePrice()*holding.getQuantity());
            holdingsCompleteData.setCurrentTotalValue(holding.getQuantity()*marketData.getPrice());
            holdingsCompleteData.setCurrentShareValue(marketData.getPrice());
            //get last dividend * 4 = yearly dividend
            List<Dividend> dividendList = marketData.getDividends();
            if (!dividendList.isEmpty()){
                int lastIndex = dividendList.size() - 1;
                Dividend dividend = dividendList.get(lastIndex);
                Double forwardDiv = dividend.getDividendAmount() * 4;
                holdingsCompleteData.setDividend(forwardDiv);
                holdingsCompleteData.setDividendYield(forwardDiv/marketData.getPrice()*100);
                holdingsCompleteData.setDividendYieldOnCost(forwardDiv/holding.getAveragePurchasePrice()*100);
                holdingsCompleteData.setTotalReceivedDividend(0.0);
            }else {
                holdingsCompleteData.setDividend(0.0);
                holdingsCompleteData.setDividendYield(0.0);
                holdingsCompleteData.setDividendYieldOnCost(0.0);
                holdingsCompleteData.setTotalReceivedDividend(0.0);
            }

            holdingsCompleteDataList.add(holdingsCompleteData);
        }
        return holdingsCompleteDataList;
    }
}
