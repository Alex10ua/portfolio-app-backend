package com.dev.alex.Repository;

import com.dev.alex.Model.SharesOutstandingHistory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SharesOutstandingHistoryRepository extends MongoRepository<SharesOutstandingHistory, String> {
}
