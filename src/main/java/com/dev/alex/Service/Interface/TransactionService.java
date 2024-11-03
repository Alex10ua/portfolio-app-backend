package com.dev.alex.Service.Interface;

import com.dev.alex.Model.Transactions;
import java.util.List;

public interface TransactionService {

    void createTransactions(List<Transactions> transactions);
    List<Transactions> findAllByPortfolioId(String portfolioId);
    List<Transactions> findAllByPortfolioIdAndTicker(String portfolioId, String ticker);
}
