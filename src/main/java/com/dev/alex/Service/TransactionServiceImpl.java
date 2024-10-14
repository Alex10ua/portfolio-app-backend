package com.dev.alex.Service;

import com.dev.alex.Model.Transactions;
import com.dev.alex.Service.Interface.TransactionService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TransactionServiceImpl implements TransactionService {

    @Override
    public void createTransactions(List<Transactions> transactions) {

    }

    @Override
    public List<Transactions> findAllTransactionByPortfolioIdAndUserId(String portfolioId, String userId) {
        return List.of();
    }
}
