package com.dev.alex.Repository;

import com.dev.alex.Model.Portfolios;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PortfolioRepository extends MongoRepository<Portfolios, String> {

    Portfolios findByPortfolioId(String portfolioId);
    Portfolios findByPortfolioIdAndUsername(String portfolioId, String username);
    List<Portfolios> findAllByUsername(String username);

    // *** FIX: Use escaped double quotes (\") for JSON keys and string literals ***
    @Query(value = "{ \"portfolioId\": :portfolioId }") // Use :portfolioId placeholder
    @Update("{ \"$set\": { \"firstTradeYear\": :firstTradeYear } }") // Use :firstTradeYear placeholder
    long updateFirstTradeYearByPortfolioId(@Param("portfolioId") String portfolioId, // @Param links method arg to placeholder
                                           @Param("firstTradeYear") LocalDate firstTradeYear);
}
