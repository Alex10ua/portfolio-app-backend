package com.dev.alex.Repository;

import com.dev.alex.Model.Portfolios;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PortfolioRepository extends MongoRepository<Portfolios, String> {

    List<Portfolios> findAllByUserId(String userId);
}
