package com.dev.alex.Repository;

import com.dev.alex.Model.Transactions;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TransacrionsRepository  extends MongoRepository<Transactions, String> {

    List<Transactions> findAllByPortfolioId(String portfolioId);
    List<Transactions> findAllByPortfolioIdAndTicker(String portfolioId, String ticker);

}
