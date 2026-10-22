package com.dev.alex.Repository;

import com.dev.alex.Model.DividendReceipt;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DividendReceiptRepository extends MongoRepository<DividendReceipt, String> {
    List<DividendReceipt> findAllByPortfolioId(String portfolioId);
    Optional<DividendReceipt> findByPortfolioIdAndTickerAndExDate(String portfolioId, String ticker, LocalDate exDate);
    void deleteAllByPortfolioId(String portfolioId);
}
