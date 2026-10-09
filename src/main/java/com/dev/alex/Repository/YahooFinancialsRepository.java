package com.dev.alex.Repository;

import com.dev.alex.Model.YahooFinancials;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface YahooFinancialsRepository extends MongoRepository<YahooFinancials, String> {
}
