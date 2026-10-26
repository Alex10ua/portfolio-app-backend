package com.dev.alex;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Service.HoldingServiceImpl;
import com.dev.alex.Service.PortfolioAccounting;
import com.dev.alex.Service.TransactionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HoldingSnapshotTests {
    @Test
    void readsLatestSplitsAndFifoFeesInBatchesWithoutUpdatingStoredHolding() {
        HoldingsRepository holdings = mock(HoldingsRepository.class);
        MarketDataRepository market = mock(MarketDataRepository.class);
        TransactionServiceImpl transactions = mock(TransactionServiceImpl.class);
        HoldingServiceImpl service = new HoldingServiceImpl();
        ReflectionTestUtils.setField(service, "holdingsRepository", holdings);
        ReflectionTestUtils.setField(service, "marketDataRepository", market);
        ReflectionTestUtils.setField(service, "transactionService", transactions);
        ReflectionTestUtils.setField(service, "portfolioAccounting", new PortfolioAccounting());

        Holdings stored = new Holdings();
        stored.setTicker("TEST");
        stored.setQuantity(BigDecimal.TEN);
        stored.setAveragePurchasePrice(new BigDecimal("100"));
        stored.setAssetType(Assets.STOCK);
        stored.setCurrency("USD");
        Transactions buy = new Transactions();
        buy.setTicker("TEST");
        buy.setAssetType(Assets.STOCK);
        buy.setTransactionType(TransactionType.BUY);
        buy.setDate(LocalDate.of(2020, 1, 1));
        buy.setQuantity(BigDecimal.TEN);
        buy.setPrice(new BigDecimal("100"));
        buy.setCommission(BigDecimal.TEN);
        buy.setCurrency("EUR");
        MarketData quote = new MarketData();
        quote.setTicker("TEST");
        quote.setPrice(new BigDecimal("50"));
        quote.setSplits(List.of(new Splits(LocalDate.of(2021, 1, 1), new BigDecimal("2"))));
        when(holdings.findAllByPortfolioId("portfolio")).thenReturn(List.of(stored));
        when(transactions.findAllByPortfolioId("portfolio")).thenReturn(List.of(buy));
        when(market.findByTickerInForHoldingsPage(anyCollection())).thenReturn(List.of(quote));

        var snapshot = service.getPortfolioHoldingsSnapshot("portfolio");
        Holdings actual = snapshot.holdings().get(0);
        assertEquals(0, new BigDecimal("20").compareTo(actual.getQuantity()));
        assertEquals(0, new BigDecimal("1010").compareTo(actual.getOpenCostBasis()));
        assertEquals(0, new BigDecimal("50.5").compareTo(actual.getAveragePurchasePrice()));
        assertEquals(BigDecimal.TEN, actual.getCommissions());
        assertEquals("EUR", actual.getCurrency());
        assertSame(quote, snapshot.marketData().get("TEST"));
        assertEquals(BigDecimal.TEN, stored.getQuantity());
        assertEquals("USD", stored.getCurrency());
        verify(holdings).findAllByPortfolioId("portfolio");
        verify(transactions).findAllByPortfolioId("portfolio");
        verify(market).findByTickerInForHoldingsPage(anyCollection());
        verifyNoMoreInteractions(holdings, transactions, market);

        quote.setSplits(List.of(new Splits(LocalDate.of(2021, 1, 1), new BigDecimal("4"))));
        assertEquals(0, new BigDecimal("40").compareTo(service.getAllHoldingsByPortfolioId("portfolio").get(0).getQuantity()));
        verify(holdings, never()).save(any());
    }
}
