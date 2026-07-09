package com.dev.alex.Service;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.NonDbModel.HoldingsCompleteData;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Service.Interface.FxRateService;
import com.dev.alex.Service.Interface.HoldingsCompleteDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dev.alex.Model.Enums.Assets;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class HoldingsCompleteDataServiceImpl implements HoldingsCompleteDataService {
        private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
        private static final BigDecimal ZERO = BigDecimal.valueOf(0);
        private static final MathContext MATH_CONTEXT = new MathContext(10, RoundingMode.HALF_EVEN);
        @Autowired
        private HoldingsRepository holdingsRepository;
        @Autowired
        private HoldingServiceImpl holdingService;
        @Autowired
        private MarketDataServiceImpl marketDataService;
        @Autowired
        private FxRateService fxRateService;

        @Override
        public List<HoldingsCompleteData> getAllHoldingsByPortfolioId(String portfolioId) {

                List<HoldingsCompleteData> holdingsCompleteDataList = new ArrayList<>();
                List<Holdings> allHolding = holdingService.getAllHoldingsByPortfolioId(portfolioId);

                // Pre-fetch market data and FX rates once for the whole portfolio to avoid
                // ~2N per-row DB round trips (one marketData + one FX lookup per holding).
                List<String> tickers = allHolding.stream()
                                .map(h -> h.getTicker() == null ? null : h.getTicker().toUpperCase())
                                .filter(java.util.Objects::nonNull).distinct().toList();
                Map<String, MarketData> marketDataByTicker = marketDataService.getMarketDataForHoldingsPage(tickers);
                Map<String, BigDecimal> fxRates = fxRateService.getAllRatesAsMap();

                for (Holdings holding : allHolding) {
                        HoldingsCompleteData holdingsCompleteData = new HoldingsCompleteData();
                        if (holding.getAssetType() == Assets.STOCK) {

                                MarketData marketData = marketDataByTicker.get(holding.getTicker().toUpperCase());

                                BigDecimal divisionResult;
                                BigDecimal dividedYieldPercentage;
                                holdingsCompleteData.setTicker(holding.getTicker().toUpperCase());
                                holdingsCompleteData.setAssetType("STOCK");
                                holdingsCompleteData.setName(marketData != null ? marketData.getName() : null);
                                holdingsCompleteData.setShareAmount(
                                                holding.getQuantity().setScale(2, RoundingMode.HALF_EVEN));
                                holdingsCompleteData
                                                .setCostPerShare(holding.getAveragePurchasePrice().setScale(2,
                                                                RoundingMode.HALF_EVEN));
                                BigDecimal costBasicTotalShare = holding.getAveragePurchasePrice()
                                                .multiply(holding.getQuantity());
                                holdingsCompleteData
                                                .setCostBasis(costBasicTotalShare.setScale(2, RoundingMode.HALF_EVEN));
                                
                                if (marketData == null || marketData.getPrice() == null) {
                                    holdingsCompleteData.setCurrentTotalValue(BigDecimal.ZERO);
                                    holdingsCompleteData.setCurrentShareValue(BigDecimal.ZERO);
                                    holdingsCompleteData.setTotalProfit(BigDecimal.ZERO);
                                    holdingsCompleteData.setTotalProfitPercentage(BigDecimal.ZERO);
                                    holdingsCompleteData.setDailyChange(BigDecimal.ZERO);
                                    holdingsCompleteDataList.add(holdingsCompleteData);
                                    continue;
                                }

                                BigDecimal currentTotalValueShares = (holding.getQuantity()
                                                .multiply(marketData.getPrice())).setScale(2,
                                                                RoundingMode.HALF_EVEN);
                                holdingsCompleteData.setCurrentTotalValue(currentTotalValueShares);
                                holdingsCompleteData.setCurrentShareValue(
                                                marketData.getPrice().setScale(2, RoundingMode.HALF_EVEN));
                                holdingsCompleteData.setSharesOutstanding(marketData.getSharesOutstanding());
                                if (marketData.getYearlyDividend() != null) {
                                        holdingsCompleteData.setDividend(marketData.getYearlyDividend());
                                        divisionResult = marketData.getYearlyDividend().divide(marketData.getPrice(),
                                                        MATH_CONTEXT);
                                        dividedYieldPercentage = divisionResult.multiply(HUNDRED);
                                        holdingsCompleteData.setDividendYield(
                                                        dividedYieldPercentage.setScale(2, RoundingMode.HALF_EVEN));

                                        if (holding.getAveragePurchasePrice() != null
                                                        && holding.getAveragePurchasePrice().compareTo(BigDecimal.ZERO) != 0) {
                                                divisionResult = marketData.getYearlyDividend().divide(
                                                                holding.getAveragePurchasePrice(),
                                                                MATH_CONTEXT);
                                                dividedYieldPercentage = divisionResult.multiply(HUNDRED);
                                                holdingsCompleteData
                                                                .setDividendYieldOnCost(dividedYieldPercentage.setScale(2,
                                                                                RoundingMode.HALF_EVEN));
                                        }
                                }
                                BigDecimal totalProfit = currentTotalValueShares.subtract(costBasicTotalShare).setScale(
                                                2,
                                                RoundingMode.HALF_EVEN);
                                holdingsCompleteData.setTotalProfit(totalProfit);

                                if (costBasicTotalShare.compareTo(BigDecimal.ZERO) != 0) {
                                        divisionResult = totalProfit.divide(costBasicTotalShare, MATH_CONTEXT);
                                        dividedYieldPercentage = divisionResult.multiply(HUNDRED);
                                        holdingsCompleteData
                                                        .setTotalProfitPercentage(dividedYieldPercentage.setScale(2,
                                                                        RoundingMode.HALF_EVEN));
                                }
                                
                                BigDecimal dailyChange = BigDecimal.ZERO;
                                if (marketData.getPriceYesterday() != null) {
                                        dailyChange = marketData.getPrice().subtract(marketData.getPriceYesterday())
                                                                        .setScale(2, RoundingMode.HALF_EVEN);
                                }
                                holdingsCompleteData.setDailyChange(dailyChange);
                                holdingsCompleteData.setCurrency(holding.getCurrency());
                                holdingsCompleteData.setFxRate(fxRateService.getRateForCurrency(holding.getCurrency(), fxRates));
                                holdingsCompleteDataList.add(holdingsCompleteData);
                        } else {
                                holdingsCompleteData.setTicker(holding.getTicker());
                                holdingsCompleteData.setAssetType(holding.getAssetType().name());
                                holdingsCompleteData.setName(holding.getName());
                                holdingsCompleteData.setShareAmount(
                                                holding.getQuantity().setScale(2, RoundingMode.HALF_EVEN));
                                holdingsCompleteData.setCostPerShare(holding.getAveragePurchasePrice());
                                BigDecimal costBasicTotalShare = holding.getAveragePurchasePrice()
                                                .multiply(holding.getQuantity());
                                holdingsCompleteData
                                                .setCostBasis(costBasicTotalShare.setScale(2, RoundingMode.HALF_EVEN));
                                MarketData marketData = marketDataByTicker.get(holding.getTicker().toUpperCase());

                                if (marketData == null || marketData.getPrice() == null) {
                                    holdingsCompleteData.setCurrentTotalValue(BigDecimal.ZERO);
                                    holdingsCompleteData.setCurrentShareValue(BigDecimal.ZERO);
                                    holdingsCompleteData.setTotalProfit(BigDecimal.ZERO);
                                    holdingsCompleteData.setTotalProfitPercentage(BigDecimal.ZERO);
                                    holdingsCompleteDataList.add(holdingsCompleteData);
                                    continue;
                                }

                                BigDecimal currentTotalValueShares = (holding.getQuantity()
                                                .multiply(marketData.getPrice())).setScale(2,
                                                                RoundingMode.HALF_EVEN);
                                holdingsCompleteData.setCurrentTotalValue(currentTotalValueShares);
                                holdingsCompleteData.setCurrentShareValue(marketData.getPrice());
                                holdingsCompleteData.setSharesOutstanding(marketData.getSharesOutstanding());
                                BigDecimal totalProfit = currentTotalValueShares.subtract(costBasicTotalShare).setScale(
                                                2,
                                                RoundingMode.HALF_EVEN);
                                holdingsCompleteData.setTotalProfit(totalProfit);
                                if (costBasicTotalShare.compareTo(BigDecimal.ZERO) != 0) {
                                        BigDecimal divisionResult = totalProfit.divide(costBasicTotalShare, MATH_CONTEXT);
                                        BigDecimal dividedYieldPercentage = divisionResult.multiply(HUNDRED);
                                        holdingsCompleteData
                                                        .setTotalProfitPercentage(dividedYieldPercentage.setScale(2,
                                                                        RoundingMode.HALF_EVEN));
                                }
                                holdingsCompleteData.setCurrency(holding.getCurrency());
                                holdingsCompleteData.setFxRate(fxRateService.getRateForCurrency(holding.getCurrency(), fxRates));
                                holdingsCompleteDataList.add(holdingsCompleteData);
                        }
                }
                return holdingsCompleteDataList;
        }
}
