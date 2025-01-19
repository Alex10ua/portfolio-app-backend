package com.dev.alex.Service.Dividends;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.DiversificationCompleteData;
import com.dev.alex.Service.MarketDataServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DiversificationUtils {

    @Autowired
    private MarketDataServiceImpl marketDataService;

    public DiversificationCompleteData getAmountsByDiversParams(List<Holdings> holdings){
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
