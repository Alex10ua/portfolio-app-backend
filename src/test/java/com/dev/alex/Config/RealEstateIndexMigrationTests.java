package com.dev.alex.Config;

import com.dev.alex.Model.Mortgage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Mocked MongoTemplate: no database. The real drop is checked against a restored backup. */
class RealEstateIndexMigrationTests {
    private final MongoTemplate template = mock(MongoTemplate.class);
    private final IndexOperations indexes = mock(IndexOperations.class);
    private final RealEstateIndexMigration migration = new RealEstateIndexMigration();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(migration, "mongoTemplate", template);
        when(template.collectionExists(Mortgage.class)).thenReturn(true);
        when(template.indexOps(Mortgage.class)).thenReturn(indexes);
    }

    @Test
    void dropsTheOneActiveLoanIndexWhenPresent() {
        List<IndexInfo> present = List.of(index("_id_"), index(RealEstateIndexMigration.LEGACY_INDEX));
        when(indexes.getIndexInfo()).thenReturn(present);
        assertTrue(migration.dropLegacyIndex());
        verify(indexes).dropIndex(RealEstateIndexMigration.LEGACY_INDEX);
    }

    @Test
    void leavesADatabaseWithoutItAlone() {
        List<IndexInfo> current = List.of(index("_id_"), index("mortgages_portfolio_property"));
        when(indexes.getIndexInfo()).thenReturn(current);
        assertFalse(migration.dropLegacyIndex());
        verify(indexes, never()).dropIndex(anyString());
    }

    @Test
    void skipsADatabaseWithNoMortgagesYet() {
        when(template.collectionExists(Mortgage.class)).thenReturn(false);
        assertFalse(migration.dropLegacyIndex());
        verify(template, never()).indexOps(Mortgage.class);
    }

    @Test
    void aFailedDropIsLoggedAndDoesNotStopStartup() {
        List<IndexInfo> present = List.of(index(RealEstateIndexMigration.LEGACY_INDEX));
        when(indexes.getIndexInfo()).thenReturn(present);
        doThrow(new IllegalStateException("not authorized")).when(indexes).dropIndex(RealEstateIndexMigration.LEGACY_INDEX);
        assertFalse(migration.dropLegacyIndex());
    }

    private static IndexInfo index(String name) {
        IndexInfo info = mock(IndexInfo.class);
        when(info.getName()).thenReturn(name);
        return info;
    }
}
