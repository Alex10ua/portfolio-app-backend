package com.dev.alex.Repository;

import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.MarketData;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface MarketDataRepository extends MongoRepository<MarketData, String> {
    @Query("{'ticker' : ?0}")
    MarketData findByTicker(String ticker);
    @Query(value = "{ 'ticker': #{#ticker} }")
    @Update("{ '$set' : {'price': :#{#price}} }")
    void updatePriceByTicker(@Param("ticker") String ticker,
                             @Param("price") BigDecimal price);

    @Query(value = "{'ticker': :#{#ticker}}",
           fields = "{'priceYesterday' : 1, 'price' : 1, 'yearlyDividend' : 1}")
    MarketData findByTickerForHoldingsPage(@Param("ticker") String ticker);

    @Query(value = "{'ticker' : #{#ticker}}")
    MarketData getDividendsAfter(@Param("ticker") String ticker);

}
