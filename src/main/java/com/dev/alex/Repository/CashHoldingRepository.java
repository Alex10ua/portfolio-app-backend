package com.dev.alex.Repository;

import com.dev.alex.Model.CashHolding;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CashHoldingRepository extends MongoRepository<CashHolding, String> {
    List<CashHolding> findAllByPortfolioId(String portfolioId);
    Optional<CashHolding> findByPortfolioIdAndCurrency(String portfolioId, String currency);
    void deleteByPortfolioIdAndCurrency(String portfolioId, String currency);
    void deleteAllByPortfolioId(String portfolioId);
}
