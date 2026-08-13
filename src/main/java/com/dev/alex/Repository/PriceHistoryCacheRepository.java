package com.dev.alex.Repository;

import com.dev.alex.Model.PriceHistoryCache;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PriceHistoryCacheRepository extends MongoRepository<PriceHistoryCache, String> {

    /**
     * Daily series only. Callers that chart day-by-day have no use for the monthly
     * one, and these documents carry decades of both.
     */
    @Query(value = "{'_id': ?0}", fields = "{'history': 1}")
    Optional<PriceHistoryCache> findDailyById(String ticker);
}
