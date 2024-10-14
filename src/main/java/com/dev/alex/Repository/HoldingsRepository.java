package com.dev.alex.Repository;

import com.dev.alex.Model.Holdings;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HoldingsRepository extends MongoRepository<Holdings, String> {

   @Query("{ 'portfolioId' : ?0 }")
   List<Holdings> findAllByPortfolioId(String portfolioId);
   @Query("{'portfolioId' : ?0, 'tickerSymbol' : ?1}")
   Holdings findByPortfolioIdAndTicker(String portfolioId, String tickerSymbol);
}
