package com.dev.alex.Repository;

import com.dev.alex.Model.CustomAsset;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

@Repository
public interface CustomAssetRepository extends MongoRepository<CustomAsset, String> {

    List<CustomAsset> findAllByPortfolioId(String portfolioId);

    @Query(value = "{'portfolioId': ?0, 'ticker': {'$in': ?1}}",
           fields = "{'ticker':1, 'country':1, 'assetType':1}")
    List<CustomAsset> findForDiversification(String portfolioId, java.util.Collection<String> tickers);

    Optional<CustomAsset> findByPortfolioIdAndTicker(String portfolioId, String ticker);

    @Query(value = "{'portfolioId': ?0, 'ticker': {'$in': ?1}}",
            fields = "{'ticker': 1, 'priceNow': 1, 'currency': 1, 'priceHistory': 1}")
    List<CustomAsset> findForPerformance(String portfolioId, Collection<String> tickers);

    boolean existsByPortfolioIdAndTicker(String portfolioId, String ticker);

    boolean existsByTickerAndPortfolioIdNot(String ticker, String portfolioId);

    void deleteAllByPortfolioId(String portfolioId);
}
