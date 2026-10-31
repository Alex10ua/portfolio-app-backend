package com.dev.alex.Repository;

import com.dev.alex.Model.Mortgage;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.Optional;

public interface MortgageRepository extends MongoRepository<Mortgage, String> {
    List<Mortgage> findAllByPortfolioId(String portfolioId);
    List<Mortgage> findAllByPortfolioIdAndPropertyId(String portfolioId, String propertyId);
    Optional<Mortgage> findByIdAndPortfolioIdAndPropertyId(String id, String portfolioId, String propertyId);
    void deleteAllByPortfolioId(String portfolioId);
}
