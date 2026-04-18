package com.dev.alex.Repository;

import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Transactions;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TransactionsRepository extends MongoRepository<Transactions, String> {

    List<Transactions> findAllByPortfolioId(String portfolioId);
    List<Transactions> findAllByPortfolioIdAndTicker(String portfolioId, String ticker);
    List<Transactions> findAllByPortfolioIdAndDateBetween(String portfolioId, LocalDate startDate, LocalDate endDate);
    List<Transactions> findAllByPortfolioIdAndDateBetweenAndTransactionTypeIn(String portfolioId, LocalDate startDate, LocalDate endDate, List<TransactionType> types);
    List<Transactions> findAllByImportBatchId(String importBatchId);
    void deleteAllByImportBatchId(String importBatchId);

}
