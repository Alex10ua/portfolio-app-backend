package com.dev.alex.Service;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.DiversificationCompleteData;
import com.dev.alex.Service.Dividends.DiversificationUtils;
import com.dev.alex.Service.Interface.DiversificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DiversificationServiceImpl  implements DiversificationService {

    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private MarketDataServiceImpl marketDataService;
    @Override
    public DiversificationCompleteData getAllDiversificationInfo(String portfolioId) {

        List<Holdings> holdings = holdingService.getAllHoldingsByPortfolioId(portfolioId);
        //DiversificationUtils diversificationUtils = new DiversificationUtils();
        DiversificationCompleteData diversificationCompleteData = new DiversificationCompleteData();
        Map<String, BigDecimal> amountByCountry = new HashMap<>();
        Map<String, BigDecimal> amountBySector = new HashMap<>();
        Map<String, BigDecimal> amountByIndustry = new HashMap<>();
        Map<String, BigDecimal> amountByStock = new HashMap<>();
        for (Holdings holding:holdings){
            MarketData marketData = marketDataService.getMarketDataByTicker(holding.getTicker());
            BigDecimal holdingValue = holding.getQuantity().multiply(marketData.getPrice()).setScale(2, RoundingMode.HALF_EVEN);
            amountByCountry.merge(marketData.getCountry(), holdingValue, BigDecimal::add);
            amountBySector.merge(marketData.getSector(), holdingValue, BigDecimal::add);
            amountByIndustry.merge(marketData.getIndustry(), holdingValue, BigDecimal::add );
            amountByStock.put(holding.getTicker(), holdingValue);
        }
        diversificationCompleteData.setAmountByCountry(amountByCountry);
        diversificationCompleteData.setAmountBySector(amountBySector);
        diversificationCompleteData.setAmountByIndustry(amountByIndustry);
        diversificationCompleteData.setAmountByStock(amountByStock);
        return diversificationCompleteData;
    }
}
