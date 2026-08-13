package com.dev.alex.Repository;

import com.dev.alex.Model.WatchlistItem;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WatchlistRepository extends MongoRepository<WatchlistItem, String> {
    List<WatchlistItem> findAllByPortfolioId(String portfolioId);
    Optional<WatchlistItem> findByPortfolioIdAndTicker(String portfolioId, String ticker);
    void deleteByPortfolioIdAndTicker(String portfolioId, String ticker);
    void deleteAllByPortfolioId(String portfolioId);
}
