package com.dev.alex.Repository;

import com.dev.alex.Model.Tickers;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TickersRepository extends MongoRepository<Tickers, String> {

    Tickers findByTicker(String ticker);
}
