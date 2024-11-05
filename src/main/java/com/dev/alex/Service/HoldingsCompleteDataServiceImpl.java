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

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class HoldingsCompleteDataServiceImpl implements HoldingsCompleteDataService {
    @Autowired
    private HoldingsRepository holdingsRepository;
    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private MarketDataRepository marketDataRepository;
    @Override
    public List<HoldingsCompleteData> getAllHoldingsByPortfolioId(String portfolioId) {

        List<HoldingsCompleteData> holdingsCompleteDataList = new ArrayList<>();
        List<Holdings> allHolding = holdingService.getAllHoldingsByPortfolioId(portfolioId);

        for (Holdings holding : allHolding){
            HoldingsCompleteData holdingsCompleteData = new HoldingsCompleteData();
            MarketData marketData = marketDataRepository.findByTicker(holding.getTicker());
            holdingsCompleteData.setName(marketData.getName());
            holdingsCompleteData.setTicker(holding.getTicker());
            holdingsCompleteData.setShareAmount(holding.getQuantity());
            holdingsCompleteData.setCostPerShare(holding.getAveragePurchasePrice());
            holdingsCompleteData.setCostBasis(holding.getAveragePurchasePrice()*holding.getQuantity());
            holdingsCompleteData.setCurrentTotalValue(holding.getQuantity()*marketData.getPrice());
            holdingsCompleteData.setCurrentShareValue(marketData.getPrice());
            //get last dividend * 4 = yearly dividend
            Double forwardDiv = marketData.getDividends().getLast().getDividendAmount()*4;
            holdingsCompleteData.setDividend(forwardDiv);
            holdingsCompleteData.setDividendYield(Double.valueOf(new DecimalFormat("##.##").format(forwardDiv/marketData.getPrice()*100)));
            holdingsCompleteData.setDividendYieldOnCost(Double.valueOf(new DecimalFormat("##.##").format(forwardDiv/holding.getAveragePurchasePrice()*100)));
            holdingsCompleteData.setTotalReceivedDividend(0.0);
            holdingsCompleteDataList.add(holdingsCompleteData);
        }
        return holdingsCompleteDataList;
    }
}
