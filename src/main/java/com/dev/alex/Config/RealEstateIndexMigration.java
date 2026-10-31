package com.dev.alex.Config;

import com.dev.alex.Model.Mortgage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Drops the unique index that allowed one active loan per property. auto-index-creation only
 * ever creates indexes, so removing the annotation from {@link Mortgage} leaves the old index in
 * every existing database, and it would keep refusing a second active loan. Idempotent.
 */
@Component
public class RealEstateIndexMigration implements ApplicationRunner {
    static final String LEGACY_INDEX = "mortgages_one_active";
    private static final Logger log = LoggerFactory.getLogger(RealEstateIndexMigration.class);

    @Autowired private MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        dropLegacyIndex();
    }

    /** Returns whether the index was found and dropped. A failure is logged, not thrown: the app still runs, single-loan. */
    boolean dropLegacyIndex() {
        try {
            if (!mongoTemplate.collectionExists(Mortgage.class)) return false;
            IndexOperations indexes = mongoTemplate.indexOps(Mortgage.class);
            if (indexes.getIndexInfo().stream().noneMatch(index -> LEGACY_INDEX.equals(index.getName()))) return false;
            indexes.dropIndex(LEGACY_INDEX);
            log.info("Dropped the {} index: a property can now hold several active loans", LEGACY_INDEX);
            return true;
        } catch (RuntimeException e) {
            log.error("Could not drop the {} index; a second active loan on a property will be refused until it is removed", LEGACY_INDEX, e);
            return false;
        }
    }
}
