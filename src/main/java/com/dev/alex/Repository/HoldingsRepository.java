package com.dev.alex.Repository;

import com.dev.alex.Model.Holdings;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HoldingsRepository extends MongoRepository<Holdings, String> {

   @Query("{ 'portfolioId' : ?0 }")
   List<Holdings> findAllByPortfolioId(String portfolioId);
   @Query("{'portfolioId' : ?0, 'ticker' : ?1}")
   Holdings findByPortfolioIdAndTicker(String portfolioId, String ticker);
   @Query(value = "{ 'holdingId': ?0 }")
   @Update("{ '$set': { 'averagePurchasePrice': :#{#averagePurchasePrice}, 'quantity': :#{#quantity} }, '$currentDate': { 'updatedAt': true } }")
   void updateAveragePurchasePriceAndQuantity(@Param("holdingId") String holdingId,
                                              @Param("averagePurchasePrice") Double averagePurchasePrice,
                                              @Param("quantity") Double quantity
   );
}
