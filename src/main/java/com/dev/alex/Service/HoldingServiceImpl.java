package com.dev.alex.Service;

import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Service.Interface.HoldingsService;
import com.dev.alex.Service.WebCalls.FlaskClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
    @Autowired
    private TickersServiceImpl tickersService;
    @Autowired
    private FlaskClientService flaskClientService;

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
    public void updateOrCreateHoldingInPortfolioUpdated(String portfolioId, Transactions newTransaction) {
        Holdings holding = findHoldingByPortfolioIdAndTicker(portfolioId, newTransaction.getTicker().toUpperCase());
        MarketData marketDataCheck = marketDataRepository.findByTicker(newTransaction.getTicker().toUpperCase());
        tickersService.saveIfNotExists(newTransaction.getTicker().toUpperCase());
        if (marketDataCheck == null) {
            //async call to flask server to get market data
            try{
                tickersService.createTicker(newTransaction.getTicker().toUpperCase());
                ResponseEntity<String> response = flaskClientService.sendSyncPostRequest(newTransaction.getTicker().toUpperCase());

                if (response != null) {
                    System.out.println("Status Code: " + response.getStatusCode());
                    System.out.println("Response Body: " + response.getBody());
                } else {
                    System.out.println("No response received");
                }
            } catch (Exception e) {
                System.out.println("Error in sync call to flask server: " + e.getMessage());
            }

        }

        if (holding == null) {
            Holdings newHolding = new Holdings();
            newHolding.setHoldingId(UUID.randomUUID().toString());
            newHolding.setPortfolioId(portfolioId);
            newHolding.setAssetType(newTransaction.getAssetType());
            newHolding.setTicker(newTransaction.getTicker().toUpperCase());
            newHolding.setQuantity(BigDecimal.ZERO);
            newHolding.setAveragePurchasePrice(BigDecimal.ZERO);
            newHolding.setCreatedAt(newTransaction.getDate());
            newHolding.setUpdatedAt(newTransaction.getDate());
            holdingsRepository.save(newHolding);

            holding = newHolding;
        }

        List<Transactions> transactionsList =
                transactionService.findAllByPortfolioIdAndTicker(portfolioId, newTransaction.getTicker().toUpperCase());
        transactionsList.sort(Comparator.comparing(Transactions::getDate));
        BigDecimal totalShares = BigDecimal.ZERO;
        BigDecimal totalCost   = BigDecimal.ZERO;
        MarketData marketData = marketDataRepository.findByTicker(newTransaction.getTicker().toUpperCase());
        List<Splits> splitsList = (marketData != null) ? marketData.getSplits() : null;

        for (Transactions tx : transactionsList) {
            BigDecimal txQuantity = tx.getQuantity();
            BigDecimal txPrice    = tx.getPrice();

            if (splitsList != null) {
                for (Splits split : splitsList) {
                    if (tx.getDate().isBefore(split.getSplitDate())) {
                        txPrice    = txPrice.divide(split.getRatioSplit(), MATH_CONTEXT);
                        txQuantity = txQuantity.multiply(split.getRatioSplit());
                    }
                }
            }

            if (tx.getTransactionType().equals(TransactionType.BUY)) {
                totalShares = totalShares.add(txQuantity);
                BigDecimal buyCost = txPrice.multiply(txQuantity, MATH_CONTEXT);
                totalCost   = totalCost.add(buyCost);

            } else if (tx.getTransactionType().equals(TransactionType.SELL)) {
                if (totalShares.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal averageCost = totalCost.divide(totalShares, MATH_CONTEXT);
                    totalShares = totalShares.subtract(txQuantity);
                    BigDecimal soldCost = averageCost.multiply(txQuantity, MATH_CONTEXT);
                    totalCost = totalCost.subtract(soldCost);

                } else {
                    throw new RuntimeException("Selling more shares than available");
                }
            }
        }
        BigDecimal finalAvgPrice;
        if (totalShares.compareTo(BigDecimal.ZERO) > 0) {
            finalAvgPrice = totalCost.divide(totalShares, MATH_CONTEXT);
        } else {
            // If no shares are left, average price might be zero or the last known cost
            finalAvgPrice = BigDecimal.ZERO;
        }

        holding.setQuantity(totalShares);
        holding.setAveragePurchasePrice(finalAvgPrice);
        holding.setUpdatedAt(newTransaction.getDate());

        holdingsRepository.save(holding);
        // remove if 0 shares
        holding = findHoldingByPortfolioIdAndTicker(portfolioId, newTransaction.getTicker().toUpperCase());
        if (holding.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            holdingsRepository.delete(holding);
        }

    }

}
