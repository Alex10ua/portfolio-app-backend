package com.dev.alex.Service;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.DividendInfoCompleteData;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Service.Dividends.DividendUtils;
import com.dev.alex.Service.Interface.DividendsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DividendsServiceImpl implements DividendsService {
    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private MarketDataServiceImpl marketDataService;
    @Autowired
    private TransactionServiceImpl transactionService;

    @Override
    public DividendInfoCompleteData getAllDividendsInfoByPortfolioId(String portfolioId) {
        DividendInfoCompleteData dividendInfoCompleteData = new DividendInfoCompleteData();
        List<Holdings> allHolding = holdingService.getAllHoldingsByPortfolioId(portfolioId);
        DividendUtils dividendUtils = new DividendUtils();
        List<Map<String, BigDecimal>> divMapList = new ArrayList<>();
        Map<String, List<Dividend>> holdingsDividends = new HashMap<>();
        Map<String, List<Transactions>> holdingsTransactions = new HashMap<>();
        Map<String, List<Splits>> holdingsSplits = new HashMap<>();
        List<String> tickers = new ArrayList<>();


        for (Holdings holding : allHolding){
            tickers.add(holding.getTicker());
            //get from holdings list of transaction for stock in holding
            List<Transactions> transactionsList = transactionService.findAllByPortfolioIdAndTicker(portfolioId, holding.getTicker());
            holdingsTransactions.put(holding.getTicker(),transactionsList);//map for calculation of dividends by month
            //get market data dividends for stock
            MarketData marketData = marketDataService.getMarketDataByTicker((holding.getTicker()));
            List<Dividend> dividendList = marketData.getDividends();
            List<Splits> splitsList = marketData.getSplits();
            holdingsDividends.put(holding.getTicker(), dividendList);
            holdingsSplits.put(holding.getTicker(), splitsList);
            //move to DividendsUtils
            BigDecimal divForStock = dividendUtils.calculateAllDividendsByStockAuto(dividendList, transactionsList, splitsList);
            Map<String, BigDecimal> allDivForStock = new HashMap<>();
            allDivForStock.put(holding.getTicker(), divForStock);
            divMapList.add(allDivForStock);

        }
        dividendInfoCompleteData.setTickerAmount(divMapList);
        dividendInfoCompleteData.setAmountByMonth(dividendUtils.calculateDividendsPerMonthAuto(holdingsTransactions, holdingsDividends, holdingsSplits, tickers));

        return dividendInfoCompleteData;
    }
}
