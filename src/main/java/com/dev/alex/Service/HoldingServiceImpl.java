package com.dev.alex.Service;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.Splits;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Service.Interface.HoldingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class HoldingServiceImpl implements HoldingsService {

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
        return holdingsRepository.findByPortfolioIdAndTicker(portfolioId, tickerSymbol);
    }

    @Override
    public void updateOrCreateHoldingInPortfolio(String portfolioId, Transactions transaction) {
        Holdings holding = findHoldingByPortfolioIdAndTicker(portfolioId, transaction.getTicker());
        if (holding != null) {
            //create engagement  to holding calculate all variables
            List<Transactions> transactionsList = transactionService.findAllByPortfolioIdAndTicker(portfolioId, transaction.getTicker());
            MarketData marketData = marketDataRepository.findByTicker(transaction.getTicker());
            Double totalPrice = 0.0;
            Double quantity = 0.0;
            List<Splits> splitsList = marketData.getSplits();
            for (Transactions transactionList : transactionsList){
                Double priceList = transactionList.getPrice();
                Double quantityList = transactionList.getQuantity();
                // **Sort the splitsList by splitDate in ascending order**
                if (splitsList != null) {
                    splitsList.sort(Comparator.comparing(Splits::getSplitDate));
                    for (Splits splits : splitsList) {
                        Date dateSplit = splits.getSplitDate();
                        if (transactionList.getDate().before(dateSplit)) {
                            priceList = priceList / splits.getRatioSplit();
                            quantityList = quantityList * splits.getRatioSplit();
                        }
                    }
                }

                quantity += quantityList;
                totalPrice += priceList;
            }
            Double avgPrice = totalPrice/quantity;

            holdingsRepository.updateAveragePurchasePriceAndQuantity(holding.getHoldingId(), avgPrice, quantity);

        }
        else {
            Holdings newHolding = new Holdings();
            newHolding.setHoldingId(UUID.randomUUID().toString());
            newHolding.setPortfolioId(portfolioId);
            newHolding.setAssetType(transaction.getAssetType());
            newHolding.setTicker(transaction.getTicker());
            newHolding.setQuantity(transaction.getQuantity());
            newHolding.setAveragePurchasePrice(transaction.getPrice());
            newHolding.setCreatedAt(transaction.getDate());
            newHolding.setUpdatedAt(transaction.getDate());
            holdingsRepository.save(newHolding);
        }
    }

}
