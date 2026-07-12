package com.dev.alex.Repository;

import com.dev.alex.Model.TickerNotes;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TickerNotesRepository extends MongoRepository<TickerNotes, String> {
    Optional<TickerNotes> findByUsernameAndPortfolioIdAndTicker(String username, String portfolioId, String ticker);
    void deleteAllByPortfolioId(String portfolioId);
}
