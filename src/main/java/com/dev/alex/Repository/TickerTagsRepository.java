package com.dev.alex.Repository;

import com.dev.alex.Model.TickerTags;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TickerTagsRepository extends MongoRepository<TickerTags, String> {
    Optional<TickerTags> findByUsernameAndPortfolioIdAndTicker(String username, String portfolioId, String ticker);
    List<TickerTags> findAllByUsernameAndPortfolioId(String username, String portfolioId);
    List<TickerTags> findAllByUsername(String username);
}
