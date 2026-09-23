package com.dev.alex.Service;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.NonDbModel.HoldingsCompleteData;
import com.dev.alex.Model.MarketData;
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
        private static final MathContext MATH_CONTEXT = new MathContext(10, RoundingMode.HALF_EVEN);
        @Autowired
        private HoldingServiceImpl holdingService;
        @Autowired
        private MarketDataServiceImpl marketDataService;
        @Autowired
        private FxRateService fxRateService;

        /**
         * One row per holding. Market figures (price, value, daily change, dividend) stay in the
         * provider's quote currency and cost figures in the book currency — see
         * {@link HoldingsCompleteData#getQuoteCurrency()}. Profit is only computed here when the
         * two agree. Otherwise it would subtract a EUR cost from a USD value (a CoinGecko coin
         * bought in EUR) or pounds from pence (a London listing), so it is left null for the
         * client to derive once it has converted the value.
         */
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
                        HoldingsCompleteData row = new HoldingsCompleteData();
                        boolean isStock = holding.getAssetType() == Assets.STOCK;
                        MarketData marketData = marketDataByTicker.get(holding.getTicker().toUpperCase());

                        row.setTicker(isStock ? holding.getTicker().toUpperCase() : holding.getTicker());
                        row.setAssetType(isStock ? "STOCK" : holding.getAssetType().name());
                        row.setName(isStock ? (marketData != null ? marketData.getName() : null) : holding.getName());
                        row.setShareAmount(holding.getQuantity().setScale(2, RoundingMode.HALF_EVEN));
                        row.setExactShareAmount(holding.getQuantity());
                        row.setCostPerShare(isStock
                                        ? holding.getAveragePurchasePrice().setScale(2, RoundingMode.HALF_EVEN)
                                        : holding.getAveragePurchasePrice());
                        BigDecimal costBasis = holding.getAveragePurchasePrice().multiply(holding.getQuantity());
                        row.setCostBasis(costBasis.setScale(2, RoundingMode.HALF_EVEN));
                        row.setCurrency(holding.getCurrency());
                        row.setFxRate(fxRateService.getRateForCurrency(holding.getCurrency(), fxRates));

                        if (marketData == null || marketData.getPrice() == null) {
                                row.setCurrentTotalValue(BigDecimal.ZERO);
                                row.setCurrentShareValue(BigDecimal.ZERO);
                                row.setTotalProfit(BigDecimal.ZERO);
                                row.setTotalProfitPercentage(BigDecimal.ZERO);
                                if (isStock) row.setDailyChange(BigDecimal.ZERO);
                                holdingsCompleteDataList.add(row);
                                continue;
                        }

                        String quoteCurrency = marketData.getCurrency() == null || marketData.getCurrency().isBlank()
                                        ? null : marketData.getCurrency();
                        row.setQuoteCurrency(quoteCurrency);
                        boolean sameCurrency = quoteCurrency == null || holding.getCurrency() == null
                                        || quoteCurrency.equals(holding.getCurrency());

                        BigDecimal currentTotalValue = holding.getQuantity().multiply(marketData.getPrice())
                                        .setScale(2, RoundingMode.HALF_EVEN);
                        row.setCurrentTotalValue(currentTotalValue);
                        row.setCurrentShareValue(isStock
                                        ? marketData.getPrice().setScale(2, RoundingMode.HALF_EVEN)
                                        : marketData.getPrice());
                        row.setSharesOutstanding(marketData.getSharesOutstanding());

                        if (sameCurrency) {
                                BigDecimal totalProfit = currentTotalValue.subtract(costBasis)
                                                .setScale(2, RoundingMode.HALF_EVEN);
                                row.setTotalProfit(totalProfit);
                                if (costBasis.compareTo(BigDecimal.ZERO) != 0) {
                                        row.setTotalProfitPercentage(totalProfit.divide(costBasis, MATH_CONTEXT)
                                                        .multiply(HUNDRED).setScale(2, RoundingMode.HALF_EVEN));
                                }
                        }

                        if (isStock) {
                                if (marketData.getYearlyDividend() != null) {
                                        row.setDividend(marketData.getYearlyDividend());
                                        row.setDividendYield(marketData.getYearlyDividend()
                                                        .divide(marketData.getPrice(), MATH_CONTEXT)
                                                        .multiply(HUNDRED).setScale(2, RoundingMode.HALF_EVEN));
                                        if (sameCurrency && holding.getAveragePurchasePrice() != null
                                                        && holding.getAveragePurchasePrice().compareTo(BigDecimal.ZERO) != 0) {
                                                row.setDividendYieldOnCost(marketData.getYearlyDividend()
                                                                .divide(holding.getAveragePurchasePrice(), MATH_CONTEXT)
                                                                .multiply(HUNDRED).setScale(2, RoundingMode.HALF_EVEN));
                                        }
                                }
                                BigDecimal dailyChange = BigDecimal.ZERO;
                                if (marketData.getPriceYesterday() != null) {
                                        dailyChange = marketData.getPrice().subtract(marketData.getPriceYesterday())
                                                        .setScale(2, RoundingMode.HALF_EVEN);
                                }
                                row.setDailyChange(dailyChange);
                        }
                        holdingsCompleteDataList.add(row);
                }
                return holdingsCompleteDataList;
        }
}
