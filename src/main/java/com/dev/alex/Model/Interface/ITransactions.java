package com.dev.alex.Model.Interface;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.dev.alex.Model.Portfolios;
import com.dev.alex.Model.Transactions;

public interface ITransactions extends MongoRepository<Transactions, String> {

    @Query("{portfolioId:'?0'}")
    List<Transactions> geTransactionsByPortfolioId(Portfolios portfolioId);

}
