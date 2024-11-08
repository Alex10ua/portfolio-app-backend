package com.dev.alex.Repository;

import com.dev.alex.Model.MarketData;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketDataRepository extends MongoRepository<MarketData, String> {
    @Query("{'ticker' : ?0}")
    MarketData findByTicker(String ticker);
}
