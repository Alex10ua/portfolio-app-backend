package com.dev.alex.Repository;

import com.dev.alex.Model.ImportBatch;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ImportBatchRepository extends MongoRepository<ImportBatch, String> {
    List<ImportBatch> findAllByPortfolioIdOrderByUploadedAtDesc(String portfolioId);
}
