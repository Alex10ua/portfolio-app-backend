package com.dev.alex.Repository;

import com.dev.alex.Model.MonthlyPriceHistory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MonthlyPriceHistoryRepository extends MongoRepository<MonthlyPriceHistory, String> {
    /** Monthly series only — the daily one in the same document is large and unused here. */
    @Query(value = "{'_id': {'$in': ?0}}", fields = "{'monthlyHistory': 1}")
    List<MonthlyPriceHistory> findMonthlyByTickerIn(java.util.Collection<String> tickers);
}
