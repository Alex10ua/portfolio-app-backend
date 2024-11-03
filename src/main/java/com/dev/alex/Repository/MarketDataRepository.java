package com.dev.alex.Repository;

import com.dev.alex.Model.MarketData;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketDataRepository extends MongoRepository<MarketData, String> {

    MarketData findByTicker(String ticker);
}
