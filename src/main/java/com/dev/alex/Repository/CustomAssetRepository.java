package com.dev.alex.Repository;

import com.dev.alex.Model.CustomAsset;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomAssetRepository extends MongoRepository<CustomAsset, String> {

    List<CustomAsset> findAllByPortfolioId(String portfolioId);

    Optional<CustomAsset> findByPortfolioIdAndTicker(String portfolioId, String ticker);

    boolean existsByPortfolioIdAndTicker(String portfolioId, String ticker);
}
