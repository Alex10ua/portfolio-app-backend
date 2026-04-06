package com.dev.alex.Service;

import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.DiversificationCompleteData;
import com.dev.alex.Service.Interface.DiversificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class DiversificationServiceImpl implements DiversificationService {

    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private MarketDataServiceImpl marketDataService;
    @Autowired
    private CustomAssetServiceImpl customAssetService;

    @Override
    public DiversificationCompleteData getAllDiversificationInfo(String portfolioId) {

        List<Holdings> holdings = holdingService.getAllHoldingsByPortfolioId(portfolioId);
        // DiversificationUtils diversificationUtils = new DiversificationUtils();
        DiversificationCompleteData diversificationCompleteData = new DiversificationCompleteData();
        Map<String, BigDecimal> amountByCountry = new HashMap<>();
        Map<String, BigDecimal> amountBySector = new HashMap<>();
        Map<String, BigDecimal> amountByIndustry = new HashMap<>();
        Map<String, BigDecimal> amountByStock = new HashMap<>();
        for (Holdings holding : holdings) {
            MarketData marketData = marketDataService.getMarketDataByTicker(holding.getTicker());
            if (marketData == null || marketData.getPrice() == null) {
                continue;
            }
            BigDecimal holdingValue = holding.getQuantity().multiply(marketData.getPrice()).setScale(2,
                    RoundingMode.HALF_EVEN);
            if (holding.getAssetType() == Assets.STOCK) {
                String country = marketData.getCountry() != null ? marketData.getCountry() : "Unknown";
                String sector = marketData.getSector() != null ? marketData.getSector() : "Unknown";
                String industry = marketData.getIndustry() != null ? marketData.getIndustry() : "Unknown";
                amountByCountry.merge(country, holdingValue, BigDecimal::add);
                amountBySector.merge(sector, holdingValue, BigDecimal::add);
                amountByIndustry.merge(industry, holdingValue, BigDecimal::add);
            } else if (holding.getAssetType() == Assets.CUSTOM) {
                // Look up country and assetType label from the CustomAsset definition
                Optional<CustomAsset> customAssetOpt = customAssetService.findOptionalByPortfolioIdAndTicker(portfolioId, holding.getTicker());
                String country = customAssetOpt
                        .map(ca -> ca.getCountry() != null ? ca.getCountry() : "Unknown")
                        .orElse("Unknown");
                String sector = customAssetOpt
                        .map(ca -> ca.getAssetType() != null ? ca.getAssetType() : "Custom")
                        .orElse("Custom");
                amountByCountry.merge(country, holdingValue, BigDecimal::add);
                amountBySector.merge(sector, holdingValue, BigDecimal::add);
            } else {
                // Legacy non-stock types (COIN, FIGURINE, FUND, CRYPTO)
                String assetCategory = holding.getAssetType() != null ? holding.getAssetType().name() : "OTHER";
                String country = marketData.getCountry() != null ? marketData.getCountry() : "N/A";
                amountByCountry.merge(country, holdingValue, BigDecimal::add);
                amountBySector.merge(assetCategory, holdingValue, BigDecimal::add);
            }
            amountByStock.put(holding.getTicker(), holdingValue);
        }
        if (!amountByCountry.isEmpty()) {
            diversificationCompleteData.setAmountByCountry(amountByCountry);
        }
        if (!amountBySector.isEmpty()) {
            diversificationCompleteData.setAmountBySector(amountBySector);
        }
        if (!amountByIndustry.isEmpty()) {
            diversificationCompleteData.setAmountByIndustry(amountByIndustry);
        }
        if (!amountByStock.isEmpty()) {
            diversificationCompleteData.setAmountByStock(amountByStock);
        }
        return diversificationCompleteData;
    }
}
