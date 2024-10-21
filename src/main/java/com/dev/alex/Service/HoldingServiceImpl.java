package com.dev.alex.Service;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Service.Interface.HoldingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class HoldingServiceImpl implements HoldingsService {

    @Autowired
    private HoldingsRepository holdingsRepository;
    @Autowired
    private TransactionServiceImpl transactionService;

    @Override
    public List<Holdings> getAllHoldingsByPortfolioId(String portfolioId) {
       return holdingsRepository.findAllByPortfolioId(portfolioId);
    }

    @Override
    public void updateHoldingsByHoldingId(String holdingId) {

    }

    @Override
    public void updateHoldingByPortfolioIdAndTickerSymbol(String portfolioId, String tickerSymbol, Holdings holding) {

    }

    @Override
    public Holdings findHoldingByPortfolioIdAndTicker(String portfolioId, String tickerSymbol) {
        return holdingsRepository.findByPortfolioIdAndTicker(portfolioId, tickerSymbol);
    }

    @Override
    public void updateOrCreateHoldingInPortfolio(String portfolioId, Transactions transaction) {
        Holdings holding = findHoldingByPortfolioIdAndTicker(portfolioId, transaction.getTickerSymbol());
        if (holding != null) {
            //create engagement  to holding calculate all variables
            List<Transactions> transactionsList = transactionService.findAllByPortfolioId(portfolioId);
            Double avgPrice = 0.0;
            Double quantity = 0.0;
            for (Transactions transactionList : transactionsList){
                Double priceList = transactionList.getPrice();
                Double quantityList = transactionList.getQuantity();
                quantity = quantity + quantityList;
                avgPrice = avgPrice + priceList;
            }
            avgPrice = avgPrice/transactionsList.size();

            holdingsRepository.updateAveragePurchasePriceAndQuantity(holding.getHoldingId(), avgPrice, quantity);


        }
        else {
            Holdings newHolding = new Holdings();
            newHolding.setHoldingId(UUID.randomUUID().toString());
            newHolding.setPortfolioId(portfolioId);
            newHolding.setAssetType(transaction.getAssetType());
            newHolding.setTickerSymbol(transaction.getTickerSymbol());
            newHolding.setLogoBase64(null);
            newHolding.setQuantity(transaction.getQuantity());
            newHolding.setAveragePurchasePrice(transaction.getPrice());
            newHolding.setCreatedAt(transaction.getDate());
            newHolding.setUpdatedAt(transaction.getDate());
            holdingsRepository.save(newHolding);
        }
    }

    @Override
    public void updateAvgPriveAndQuantity(String holdingId, Double avgPrice, Double quantity) {

    }


}
