package com.dev.alex.Service.Interface;

import com.dev.alex.Model.Transactions;
import java.util.List;

public interface TransactionService {

    void createTransactions(List<Transactions> transactions);

    List<Transactions> findAllByPortfolioId(String portfolioId);

    List<Transactions> findAllByPortfolioIdAndTicker(String portfolioId, String ticker);

    List<Transactions> findAllByPortfolioIdAndYear(String portfolioId, int year);

    List<Transactions> findBuySellByPortfolioIdAndYear(String portfolioId, int year);

    void updateTransaction(Transactions transaction, String transactionId, String portfolioId);

    void deleteTransaction(String transactionId, String portfolioId);

}
