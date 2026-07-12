package com.dev.alex.Repository;

import com.dev.alex.Model.RealizedPnlCache;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RealizedPnlCacheRepository extends MongoRepository<RealizedPnlCache, String> {
}
