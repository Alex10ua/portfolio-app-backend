package com.dev.alex.Repository;

import com.dev.alex.Model.Transactions;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionsRepository extends MongoRepository<Transactions, String> {

    List<Transactions> findAllByPortfolioId(String portfolioId);
    List<Transactions> findAllByPortfolioIdAndTicker(String portfolioId, String ticker);

}
