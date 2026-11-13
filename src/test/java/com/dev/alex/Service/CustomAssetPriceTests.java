package com.dev.alex.Service;

import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Model.NonDbModel.PriceHistoryEntry;
import com.dev.alex.Repository.CustomAssetRepository;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.MarketDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CustomAssetPriceTests {
    private final CustomAssetRepository assets = mock(CustomAssetRepository.class);
    private final MarketDataRepository market = mock(MarketDataRepository.class);
    private final HoldingsRepository holdings = mock(HoldingsRepository.class);
    private final CustomAssetServiceImpl service = new CustomAssetServiceImpl();
    private CustomAsset asset;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "customAssetRepository", assets);
        ReflectionTestUtils.setField(service, "marketDataRepository", market);
        ReflectionTestUtils.setField(service, "holdingsRepository", holdings);
        when(assets.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        asset = new CustomAsset();
        asset.setPortfolioId("p");
        asset.setTicker("FUND");
        asset.setCreatedAt(LocalDate.of(2024, 1, 1));
        asset.setPriceHistory(new ArrayList<>(List.of(point("2025-06-30", "12"))));
        asset.setPriceNow(new BigDecimal("12"));
        when(assets.findByPortfolioIdAndTicker("p", "FUND")).thenReturn(Optional.of(asset));
    }

    @Test
    void importingAnOlderStatementKeepsTheCurrentPrice() {
        CustomAsset saved = service.bulkMergePriceHistory("p", "FUND",
                List.of(point("2024-03-31", "10"), point("2024-04-30", "10.5")));

        assertEquals(0, new BigDecimal("12").compareTo(saved.getPriceNow()));
        assertEquals(3, saved.getPriceHistory().size());
        verify(holdings, never()).findByPortfolioIdAndTicker(anyString(), anyString());
    }

    @Test
    void importingANewerStatementMovesTheCurrentPrice() {
        CustomAsset saved = service.bulkMergePriceHistory("p", "FUND", List.of(point("2025-09-30", "13")));

        assertEquals(0, new BigDecimal("13").compareTo(saved.getPriceNow()));
        verify(holdings).findByPortfolioIdAndTicker("p", "FUND");
    }

    @Test
    void aBackDatedPriceAfterARenameBecomesCurrentWhenItIsTheNewest() {
        asset.setUpdatedAt(LocalDate.now()); // a name edit stamps updatedAt; it is not the price date

        CustomAsset saved = service.updatePrice("p", "FUND", new BigDecimal("14"), LocalDate.of(2025, 9, 30));

        assertEquals(0, new BigDecimal("14").compareTo(saved.getPriceNow()));
        assertEquals(List.of(LocalDate.of(2025, 6, 30), LocalDate.of(2025, 9, 30)),
                saved.getPriceHistory().stream().map(PriceHistoryEntry::getDate).toList());
    }

    @Test
    void aPriceOlderThanTheCurrentOneOnlyJoinsTheHistory() {
        CustomAsset saved = service.updatePrice("p", "FUND", new BigDecimal("9"), LocalDate.of(2025, 1, 31));

        assertEquals(0, new BigDecimal("12").compareTo(saved.getPriceNow()));
        assertEquals(2, saved.getPriceHistory().size());
    }

    @Test
    void rejectsTickersThatCannotBeAddressedInAUrl() {
        for (String ticker : List.of("GOLD 1/2 OZ", "A#1", "50%", "X;Y", "Q?")) {
            CustomAsset draft = new CustomAsset();
            draft.setTicker(ticker);
            assertThrows(IllegalArgumentException.class, () -> service.create("p", draft), ticker);
        }
    }

    private static PriceHistoryEntry point(String date, String price) {
        return new PriceHistoryEntry(LocalDate.parse(date), new BigDecimal(price));
    }
}
