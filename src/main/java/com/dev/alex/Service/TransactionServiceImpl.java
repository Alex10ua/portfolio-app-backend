package com.dev.alex.Service;

import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.TransacrionsRepository;
import com.dev.alex.Service.Interface.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TransactionServiceImpl implements TransactionService {
    @Autowired
    private TransacrionsRepository transacrionsRepository;

    @Override
    public void createTransactions(List<Transactions> transactions) {

    }

    @Override
    public List<Transactions> findAllByPortfolioId(String portfolioId) {
        return transacrionsRepository.findAllByPortfolioId(portfolioId);
    }

    @Override
    public List<Transactions> findAllByPortfolioIdAndTicker(String portfolioId, String ticker) {
        return transacrionsRepository.findAllByPortfolioIdAndTicker(portfolioId, ticker);
    }
}
