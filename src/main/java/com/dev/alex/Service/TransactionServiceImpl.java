package com.dev.alex.Service;

import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.TransactionsRepository;
import com.dev.alex.Service.Interface.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class TransactionServiceImpl implements TransactionService {
    @Autowired
    private TransactionsRepository transactionsRepository;

    @Override
    public void createTransactions(List<Transactions> transactions) {

    }

    @Override
    public List<Transactions> findAllByPortfolioId(String portfolioId) {
        return transactionsRepository.findAllByPortfolioId(portfolioId);
    }

    @Override
    public List<Transactions> findAllByPortfolioIdAndTicker(String portfolioId, String ticker) {
        return transactionsRepository.findAllByPortfolioIdAndTicker(portfolioId, ticker);
    }

    @Override
    public List<Transactions> findAllByPortfolioIdAndYear(String portfolioId, int year) {
        LocalDate startDate = LocalDate.of(year, 1, 1);
        LocalDate endDate = LocalDate.of(year, 12, 31);
        return transactionsRepository.findAllByPortfolioIdAndDateBetween(portfolioId, startDate, endDate);
    }
}
