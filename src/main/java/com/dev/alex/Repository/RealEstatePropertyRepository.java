package com.dev.alex.Repository;

import com.dev.alex.Model.RealEstateProperty;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.Optional;

public interface RealEstatePropertyRepository extends MongoRepository<RealEstateProperty, String> {
    List<RealEstateProperty> findAllByPortfolioId(String portfolioId);
    Optional<RealEstateProperty> findByIdAndPortfolioId(String id, String portfolioId);
    void deleteAllByPortfolioId(String portfolioId);
}
