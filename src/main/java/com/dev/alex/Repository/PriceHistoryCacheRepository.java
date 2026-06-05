package com.dev.alex.Repository;

import com.dev.alex.Model.PriceHistoryCache;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PriceHistoryCacheRepository extends MongoRepository<PriceHistoryCache, String> {
}
