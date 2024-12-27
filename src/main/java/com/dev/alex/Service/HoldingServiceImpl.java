package com.dev.alex.Service;

import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.Splits;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Service.Interface.HoldingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;

@Service
public class HoldingServiceImpl implements HoldingsService {
    private static final BigDecimal ZERO = BigDecimal.valueOf(0);
    private static final MathContext MATH_CONTEXT = new MathContext(10, RoundingMode.HALF_EVEN);

    @Autowired
    private HoldingsRepository holdingsRepository;
    @Autowired
    private TransactionServiceImpl transactionService;
    @Autowired
    private MarketDataRepository marketDataRepository;

    @Override
    public List<Holdings> getAllHoldingsByPortfolioId(String portfolioId) {
       return holdingsRepository.findAllByPortfolioId(portfolioId);
    }

    @Override
    public void updateHoldingsByHoldingId(String holdingId) {

    }

    @Override
    public void updateHoldingByPortfolioIdAndTickerSymbol(String portfolioId, String ticker, Holdings holding) {

    }

    @Override
    public Holdings findHoldingByPortfolioIdAndTicker(String portfolioId, String tickerSymbol) {
        return holdingsRepository.findByPortfolioIdAndTicker(portfolioId, tickerSymbol.toUpperCase());
    }

    @Override
    public void updateOrCreateHoldingInPortfolio(String portfolioId, Transactions transaction) {
        Holdings holding = findHoldingByPortfolioIdAndTicker(portfolioId, transaction.getTicker().toUpperCase());
        if (holding != null) {
            //create engagement  to holding calculate all variables
            List<Transactions> transactionsList = transactionService.findAllByPortfolioIdAndTicker(portfolioId, transaction.getTicker().toUpperCase());
            MarketData marketData = marketDataRepository.findByTicker(transaction.getTicker().toUpperCase());
            BigDecimal totalPrice = ZERO;
            BigDecimal quantity = ZERO;
            List<Splits> splitsList = marketData.getSplits();
            for (Transactions transactionList : transactionsList){
                if (transactionList.getTransactionType().equals(TransactionType.BUY)){
                    BigDecimal priceList = transactionList.getTotalAmount();
                    BigDecimal quantityList = transactionList.getQuantity();
                // **Sort the splitsList by splitDate in ascending order**
                if (splitsList != null) {
                    splitsList.sort(Comparator.comparing(Splits::getSplitDate));
                    for (Splits splits : splitsList) {
                        Date dateSplit = splits.getSplitDate();
                        if (transactionList.getDate().before(dateSplit)) {
                            priceList = priceList.divide(splits.getRatioSplit(), MATH_CONTEXT);
                            quantityList = quantityList.multiply(splits.getRatioSplit());
                        }
                    }
                }

                quantity = quantity.add(quantityList);
                totalPrice = totalPrice.add(priceList);
                } else if (transactionList.getTransactionType().equals(TransactionType.SELL)) {
                    BigDecimal priceList = transactionList.getTotalAmount();
                    BigDecimal quantityList = transactionList.getQuantity();
                    if (splitsList != null) {
                        splitsList.sort(Comparator.comparing(Splits::getSplitDate));
                        for (Splits splits : splitsList) {
                            Date dateSplit = splits.getSplitDate();
                            if (transactionList.getDate().before(dateSplit)) {
                                priceList = priceList.divide(splits.getRatioSplit(), MATH_CONTEXT);
                                quantityList = quantityList.multiply(splits.getRatioSplit());
                            }
                        }
                    }
                    quantity = quantity.subtract(quantityList);
                    totalPrice = totalPrice.subtract(priceList);//TODO find correct way to calculate avg price if 10 stock - 1 stock and 10 stock - 10 stock + 1 stock
                }
            }
            BigDecimal avgPrice = totalPrice.divide(quantity, MATH_CONTEXT);

            holdingsRepository.updateAveragePurchasePriceAndQuantity(holding.getHoldingId(), avgPrice, quantity);

        }
        else {
            Holdings newHolding = new Holdings();
            newHolding.setHoldingId(UUID.randomUUID().toString());
            newHolding.setPortfolioId(portfolioId);
            newHolding.setAssetType(transaction.getAssetType());
            newHolding.setTicker(transaction.getTicker().toUpperCase());
            newHolding.setQuantity(transaction.getQuantity());
            newHolding.setAveragePurchasePrice(transaction.getPrice());
            newHolding.setCreatedAt(transaction.getDate());
            newHolding.setUpdatedAt(transaction.getDate());
            holdingsRepository.save(newHolding);
        }
    }

}
