package com.dev.alex;

import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.PerformanceData;
import com.dev.alex.Model.NonDbModel.PerformancePoint;
import com.dev.alex.Model.NonDbModel.PriceHistoryEntry;
import com.dev.alex.Model.NonDbModel.PnlSummary;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.PriceHistoryCache;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.CustomAssetRepository;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Repository.PriceHistoryCacheRepository;
import com.dev.alex.Repository.RealizedPnlCacheRepository;
import com.dev.alex.Repository.TransactionsRepository;
import com.dev.alex.Service.PortfolioAccounting;
import com.dev.alex.Service.PortfolioPerformanceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic accounting and quote fixtures only; no Spring context, database, or provider calls. */
class PortfolioPerformanceTests {
    private final TransactionsRepository transactions = mock(TransactionsRepository.class);
    private final HoldingsRepository holdings = mock(HoldingsRepository.class);
    private final MarketDataRepository marketData = mock(MarketDataRepository.class);
    private final PriceHistoryCacheRepository histories = mock(PriceHistoryCacheRepository.class);
    private final CustomAssetRepository customAssets = mock(CustomAssetRepository.class);
    private final RealizedPnlCacheRepository legacyCache = mock(RealizedPnlCacheRepository.class);
    private final PortfolioPerformanceServiceImpl service = new PortfolioPerformanceServiceImpl();
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "transactionsRepository", transactions);
        ReflectionTestUtils.setField(service, "holdingsRepository", holdings);
        ReflectionTestUtils.setField(service, "marketDataRepository", marketData);
        ReflectionTestUtils.setField(service, "priceHistoryCacheRepository", histories);
        ReflectionTestUtils.setField(service, "customAssetRepository", customAssets);
        ReflectionTestUtils.setField(service, "realizedPnlCacheRepository", legacyCache);
        ReflectionTestUtils.setField(service, "accounting", new PortfolioAccounting());
        when(holdings.findAllByPortfolioId("p")).thenReturn(List.of());
        when(histories.findDailyByTickerIn(anyCollection())).thenReturn(List.of());
        when(customAssets.findForPerformance(eq("p"), anyCollection())).thenReturn(List.of());
    }

    @Test
    void fifoRemainingCostAndFeesReconcileTotalReturn() {
        when(transactions.findAllByPortfolioIdOrderByDateAsc("p")).thenReturn(List.of(
                trade(TransactionType.BUY, today.minusDays(3), "1", "100", "1"),
                trade(TransactionType.BUY, today.minusDays(2), "1", "200", "2"),
                trade(TransactionType.SELL, today.minusDays(1), "1", "300", "3")));
        when(marketData.findByTickerInForPerformance(anyCollection())).thenReturn(List.of(quote("300")));

        PerformanceData data = service.getPerformance("p", "ALL");

        amount("303", data.getTotalInvested());
        amount("202", data.getOpenCostBasisByCurrency().get("USD"));
        amount("196", data.getRealizedPnL());
        amount("98", data.getUnrealizedPnL());
        amount("294", data.getTotalReturn());
        amount("6", data.getCommissionsByCurrency().get("USD"));
        amount("4", data.getRealizedCommissionsByCurrency().get("USD"));
    }

    @Test
    void historicalValuesUseDividendUnadjustedCloseAndMatchingSplitBasis() {
        LocalDate purchase = today.minusDays(3);
        LocalDate splitDate = today.minusDays(2);
        Transactions legacyBuy = trade(TransactionType.BUY, purchase, "10", "100", "0");
        legacyBuy.setAssetType(null);
        when(transactions.findAllByPortfolioIdOrderByDateAsc("p")).thenReturn(List.of(legacyBuy));
        MarketData md = quote("60");
        md.setSplits(List.of(new Splits(splitDate, new BigDecimal("2")),
                new Splits(splitDate, new BigDecimal("2"))));
        when(marketData.findByTickerInForPerformance(anyCollection())).thenReturn(List.of(md));
        when(histories.findDailyByTickerIn(anyCollection())).thenReturn(List.of(history(List.of(
                new PriceHistoryEntry(purchase, new BigDecimal("45"), new BigDecimal("50")),
                new PriceHistoryEntry(splitDate, new BigDecimal("50"), new BigDecimal("50"))))));

        PerformanceData data = service.getPerformance("p", "ALL");

        amount("1000", data.getTimeSeries().getFirst().getPortfolioValue());
        amount("1000", data.getTimeSeries().get(1).getPortfolioValue());
        amount("1200", data.getTimeSeries().getLast().getPortfolioValue());
        amount("1200", data.getCurrentValue());
        assertTrue(data.getTimeSeries().stream().allMatch(point -> point.getEstimatedPriceTickers().isEmpty()));
    }

    @Test
    void futureTradesAreExcludedAndZeroQuoteRetainsCost() {
        when(transactions.findAllByPortfolioIdOrderByDateAsc("p")).thenReturn(List.of(
                trade(TransactionType.BUY, today.minusDays(1), "1", "100", "0"),
                trade(TransactionType.BUY, today.plusDays(1), "100", "200", "0")));
        when(marketData.findByTickerInForPerformance(anyCollection())).thenReturn(List.of(quote("0")));

        PerformanceData data = service.getPerformance("p", "ALL");

        amount("100", data.getTotalInvested());
        amount("100", data.getOpenCostBasisByCurrency().get("USD"));
        amount("0", data.getCurrentValue());
        amount("-100", data.getTotalReturn());
        assertEquals(1, data.getCashFlows().size());
    }

    @Test
    void realizedProfitRechecksSplitsInsteadOfReadingAnUnversionedCache() {
        when(transactions.findAllByPortfolioIdOrderByDateAsc("p")).thenReturn(List.of(
                trade(TransactionType.BUY, today.minusDays(3), "1", "100", "0"),
                trade(TransactionType.SELL, today.minusDays(1), "2", "60", "0")));
        MarketData before = quote("60");
        MarketData after = quote("60");
        after.setSplits(List.of(new Splits(today.minusDays(2), new BigDecimal("2"))));
        when(marketData.findByTickerInForPerformance(anyCollection()))
                .thenReturn(List.of(before), List.of(after));

        amount("-40", service.getRealizedPnLByCurrency("p").get("USD"));
        amount("20", service.getRealizedPnLByCurrency("p").get("USD"));
        verifyNoInteractions(legacyCache, histories);
    }

    @Test
    void longHistoryKeepsItsBoundsAndBatchesInputsOnce() {
        LocalDate purchase = today.minusYears(25);
        when(transactions.findAllByPortfolioIdOrderByDateAsc("p")).thenReturn(List.of(
                trade(TransactionType.BUY, purchase, "1", "100", "0")));
        when(marketData.findByTickerInForPerformance(anyCollection())).thenReturn(List.of(quote("110")));
        // A legacy entry still works; a normal provider refresh later supplies rawPrice.
        when(histories.findDailyByTickerIn(anyCollection())).thenReturn(List.of(history(List.of(
                new PriceHistoryEntry(purchase, new BigDecimal("100"))))));

        List<PerformancePoint> series = service.getPerformance("p", "ALL").getTimeSeries();

        assertEquals(200, series.size());
        assertEquals(purchase, series.getFirst().getDate());
        assertEquals(today, series.getLast().getDate());
        amount("100", series.getFirst().getPortfolioValue());
        amount("110", series.getLast().getPortfolioValue());
        assertEquals(List.of("TEST"), series.getFirst().getEstimatedPriceTickers());
        assertTrue(series.getLast().getEstimatedPriceTickers().isEmpty());
        verify(transactions, times(1)).findAllByPortfolioIdOrderByDateAsc("p");
        verify(marketData, times(1)).findByTickerInForPerformance(anyCollection());
        verify(histories, times(1)).findDailyByTickerIn(anyCollection());
        verifyNoInteractions(legacyCache);
    }

    @Test
    void pnlSummaryReturnsFeeBreakdownWithoutLoadingHoldingsOrHistories() {
        when(transactions.findAllByPortfolioIdOrderByDateAsc("p")).thenReturn(List.of(
                trade(TransactionType.BUY, today.minusDays(2), "2", "100", "2"),
                trade(TransactionType.SELL, today.minusDays(1), "1", "150", "3")));
        when(marketData.findByTickerInForPerformance(anyCollection())).thenReturn(List.of(quote("150")));

        PnlSummary summary = service.getPnlSummary("p");

        amount("46", summary.realizedPnLByCurrency().get("USD"));
        amount("5", summary.commissionsByCurrency().get("USD"));
        amount("4", summary.realizedCommissionsByCurrency().get("USD"));
        verify(transactions, times(1)).findAllByPortfolioIdOrderByDateAsc("p");
        verify(marketData, times(1)).findByTickerInForPerformance(anyCollection());
        verifyNoInteractions(holdings, histories, customAssets, legacyCache);
    }

    @Test
    void customQuoteUsesThePortfolioDefinitionBeforeTheSharedStub() {
        Transactions buy = trade(TransactionType.BUY, today.minusDays(1), "1", "10", "0");
        buy.setAssetType(Assets.CUSTOM);
        when(transactions.findAllByPortfolioIdOrderByDateAsc("p")).thenReturn(List.of(buy));
        when(marketData.findByTickerInForPerformance(anyCollection())).thenReturn(List.of(quote("999")));
        CustomAsset ownAsset = new CustomAsset();
        ownAsset.setTicker("TEST");
        ownAsset.setCurrency("USD");
        ownAsset.setPriceNow(new BigDecimal("12"));
        when(customAssets.findForPerformance(eq("p"), anyCollection())).thenReturn(List.of(ownAsset));

        amount("12", service.getPerformance("p", "ALL").getCurrentValue());
        verifyNoInteractions(histories);
    }

    private Transactions trade(TransactionType type, LocalDate date, String quantity, String price, String fee) {
        Transactions tx = new Transactions();
        tx.setTicker("TEST");
        tx.setAssetType(Assets.STOCK);
        tx.setCurrency("USD");
        tx.setTransactionType(type);
        tx.setDate(date);
        tx.setQuantity(new BigDecimal(quantity));
        tx.setPrice(new BigDecimal(price));
        tx.setCommission(new BigDecimal(fee));
        return tx;
    }

    private MarketData quote(String price) {
        MarketData md = new MarketData();
        md.setTicker("TEST");
        md.setCurrency("USD");
        md.setPrice(new BigDecimal(price));
        return md;
    }

    private PriceHistoryCache history(List<PriceHistoryEntry> entries) {
        PriceHistoryCache cache = new PriceHistoryCache();
        cache.setTicker("TEST");
        cache.setHistory(entries);
        return cache;
    }

    private void amount(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "Expected " + expected + ", got " + actual);
    }
}
