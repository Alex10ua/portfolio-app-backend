package com.dev.alex.Repository;

import com.dev.alex.Model.FxRate;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FxRateRepository extends MongoRepository<FxRate, String> {}
