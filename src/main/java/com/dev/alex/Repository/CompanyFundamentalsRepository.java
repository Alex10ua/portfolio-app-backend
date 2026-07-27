package com.dev.alex.Repository;

import com.dev.alex.Model.CompanyFundamentals;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CompanyFundamentalsRepository extends MongoRepository<CompanyFundamentals, String> {
}
