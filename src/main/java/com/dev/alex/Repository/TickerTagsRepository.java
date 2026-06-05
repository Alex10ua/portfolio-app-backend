package com.dev.alex.Repository;

import com.dev.alex.Model.TickerTags;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TickerTagsRepository extends MongoRepository<TickerTags, String> {
    Optional<TickerTags> findByUsernameAndTicker(String username, String ticker);
    List<TickerTags> findAllByUsername(String username);
}
