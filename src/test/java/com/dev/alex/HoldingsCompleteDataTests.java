package com.dev.alex;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.HoldingsCompleteData;
import com.dev.alex.Service.HoldingServiceImpl;
import com.dev.alex.Service.HoldingsCompleteDataServiceImpl;
import com.dev.alex.Service.Interface.FxRateService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HoldingsCompleteDataTests {
    @Test
    void unrealizedLossIncludesOpenPurchaseFeesExactlyOnce() {
        HoldingsCompleteData row = rowAtPrice(new BigDecimal("100"));
        assertEquals(0, new BigDecimal("1010").compareTo(row.getCostBasis()));
        assertEquals(0, BigDecimal.TEN.compareTo(row.getCommissions()));
        assertEquals(0, new BigDecimal("-10").compareTo(row.getTotalProfit()));
        assertEquals(Map.of("USD", new BigDecimal("1010")), row.getCostBasisByCurrency());
    }

    @Test
    void zeroMarketPriceRetainsBasisAndDoesNotDivideByZeroForDividendYield() {
        HoldingsCompleteData row = rowAtPrice(BigDecimal.ZERO);
        assertEquals(0, new BigDecimal("1010").compareTo(row.getCostBasis()));
        assertEquals(0, new BigDecimal("-1010").compareTo(row.getTotalProfit()));
        assertNull(row.getDividendYield());
    }

    @Test
    void missingQuoteKeepsKnownBasisButLeavesMarketValueAndProfitUnavailable() {
        HoldingsCompleteData row = rowAtPrice(null);
        assertEquals(0, new BigDecimal("1010").compareTo(row.getCostBasis()));
        assertEquals(0, BigDecimal.TEN.compareTo(row.getCommissions()));
        assertEquals("USD", row.getQuoteCurrency());
        assertNull(row.getCurrentShareValue());
        assertNull(row.getCurrentTotalValue());
        assertNull(row.getTotalProfit());
        assertNull(row.getTotalProfitPercentage());
        assertNull(row.getDailyChange());
    }

    private HoldingsCompleteData rowAtPrice(BigDecimal price) {
        Holdings holding = new Holdings();
        holding.setTicker("TEST");
        holding.setAssetType(Assets.STOCK);
        holding.setCurrency("USD");
        holding.setQuantity(BigDecimal.TEN);
        holding.setAveragePurchasePrice(new BigDecimal("101"));
        holding.setOpenCostBasis(new BigDecimal("1010"));
        holding.setCommissions(BigDecimal.TEN);
        holding.setCostBasisByCurrency(Map.of("USD", new BigDecimal("1010")));
        holding.setCommissionsByCurrency(Map.of("USD", BigDecimal.TEN));
        MarketData quote = new MarketData();
        quote.setTicker("TEST");
        quote.setCurrency("USD");
        quote.setPrice(price);
        quote.setYearlyDividend(BigDecimal.ONE);
        HoldingServiceImpl holdings = mock(HoldingServiceImpl.class);
        when(holdings.getPortfolioHoldingsSnapshot("portfolio"))
                .thenReturn(new HoldingServiceImpl.HoldingsSnapshot(List.of(holding), Map.of("TEST", quote)));
        FxRateService fx = mock(FxRateService.class);
        when(fx.getAllRatesAsMap()).thenReturn(Map.of("USD", BigDecimal.ONE));
        HoldingsCompleteDataServiceImpl service = new HoldingsCompleteDataServiceImpl();
        ReflectionTestUtils.setField(service, "holdingService", holdings);
        ReflectionTestUtils.setField(service, "fxRateService", fx);
        return service.getAllHoldingsByPortfolioId("portfolio").get(0);
    }
}
