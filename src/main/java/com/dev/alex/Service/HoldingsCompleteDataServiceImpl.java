package com.dev.alex.Service;

import com.dev.alex.Model.Dividend;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.HoldingsCompleteData;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Service.Interface.HoldingsCompleteDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class HoldingsCompleteDataServiceImpl implements HoldingsCompleteDataService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO = BigDecimal.valueOf(0);
    private static final MathContext MATH_CONTEXT = new MathContext(10, RoundingMode.HALF_EVEN);
    private static final MathContext PRECISION_2_HALF_EVEN = new MathContext(3, RoundingMode.HALF_EVEN);
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
            if (marketData.getPrice() == null || marketData.getPrice().compareTo(BigDecimal.ZERO) == 0){
                throw new IllegalArgumentException("Price must not be null or zero.");
            }
            BigDecimal divisionResult;
            BigDecimal dividedYieldPercentage;
            holdingsCompleteData.setName(marketData.getName());
            holdingsCompleteData.setTicker(holding.getTicker().toUpperCase());
            holdingsCompleteData.setShareAmount(holding.getQuantity().setScale(2, RoundingMode.HALF_EVEN));
            holdingsCompleteData.setCostPerShare(holding.getAveragePurchasePrice().setScale(2, RoundingMode.HALF_EVEN));
            BigDecimal costBasicTotalShare = holding.getAveragePurchasePrice().multiply(holding.getQuantity());
            holdingsCompleteData.setCostBasis(costBasicTotalShare.setScale(2, RoundingMode.HALF_EVEN));
            BigDecimal currentTotalValueShares = (holding.getQuantity().multiply(marketData.getPrice())).setScale(2, RoundingMode.HALF_EVEN);
            holdingsCompleteData.setCurrentTotalValue(currentTotalValueShares);
            holdingsCompleteData.setCurrentShareValue(marketData.getPrice().setScale(2, RoundingMode.HALF_EVEN));
            //get last dividend * 4 = yearly dividend
            List<Dividend> dividendList = marketData.getDividends();
            if (!dividendList.isEmpty()){
                int lastIndex = dividendList.size() - 1;
                Dividend dividend = dividendList.get(lastIndex);
                BigDecimal forwardDiv = dividend.getDividendAmount().multiply(BigDecimal.valueOf(4));
                holdingsCompleteData.setDividend(forwardDiv);
                divisionResult = forwardDiv.divide(marketData.getPrice(), MATH_CONTEXT);
                dividedYieldPercentage = divisionResult.multiply(HUNDRED);
                holdingsCompleteData.setDividendYield(dividedYieldPercentage.setScale(2, RoundingMode.HALF_EVEN));
                divisionResult = forwardDiv.divide(holding.getAveragePurchasePrice(), MATH_CONTEXT);
                dividedYieldPercentage = divisionResult.multiply(HUNDRED);
                holdingsCompleteData.setDividendYieldOnCost(dividedYieldPercentage.setScale(2, RoundingMode.HALF_EVEN));
                holdingsCompleteData.setTotalReceivedDividend(ZERO);
            }else {
                holdingsCompleteData.setDividend(ZERO);
                holdingsCompleteData.setDividendYield(ZERO);
                holdingsCompleteData.setDividendYieldOnCost(ZERO);
                holdingsCompleteData.setTotalReceivedDividend(ZERO);
            }
            BigDecimal totalProfit = currentTotalValueShares.subtract(costBasicTotalShare).setScale(2, RoundingMode.HALF_EVEN);
            holdingsCompleteData.setTotalProfit(totalProfit);

            divisionResult = totalProfit.divide(costBasicTotalShare, MATH_CONTEXT);
            dividedYieldPercentage = divisionResult.multiply(HUNDRED);
            holdingsCompleteData.setTotalProfitPercentage(dividedYieldPercentage.setScale(2, RoundingMode.HALF_EVEN));
            holdingsCompleteData.setDailyChange(marketData.getPrice().subtract(marketData.getPriceYesterday()).setScale(2,RoundingMode.HALF_EVEN));
            holdingsCompleteDataList.add(holdingsCompleteData);
        }
        return holdingsCompleteDataList;
    }
}
