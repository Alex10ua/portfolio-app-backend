package com.dev.alex.Service;

import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.DividendsCalendarData;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.CustomAssetRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Service.Dividends.DividendUtils;
import com.dev.alex.Service.Interface.FxRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncomeQueryBatchingTest {
    private final HoldingServiceImpl holdings = mock(HoldingServiceImpl.class);
    private final TransactionServiceImpl transactions = mock(TransactionServiceImpl.class);
    private final MarketDataRepository marketRepository = mock(MarketDataRepository.class);
    private final MarketDataServiceImpl marketData = new MarketDataServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(marketData, "marketDataRepository", marketRepository);
    }

    @Test
    void dividendsReuseOneMarketReadForHistoricalIncomeAndCurrentProjection() {
        FxRateService fx = mock(FxRateService.class);
        when(fx.getAllRatesAsMap()).thenReturn(Map.of("USD", BigDecimal.ONE));
        when(holdings.getAllHoldingsByPortfolioId("p")).thenReturn(List.of(holding("A", Assets.STOCK, "3")));
        when(transactions.findAllByPortfolioId("p")).thenReturn(List.of(
                trade("A", "2024-01-10", TransactionType.SELL, "2"),
                trade("B", "2024-03-01", TransactionType.SELL, "2"),
                trade("A", "2024-01-01", TransactionType.BUY, "5"),
                trade("B", "2024-02-01", TransactionType.BUY, "2")));
        MarketData a = market("A", "USD", "100", dividend("2024-01-02", "0.5"));
        a.setYearlyDividend(new BigDecimal("2"));
        MarketData b = market("B", "EUR", "50", dividend("2024-02-02", "2"));
        when(marketRepository.findByTickerInForIncome(any())).thenReturn(List.of(a, b));

        var result = new DividendsServiceImpl(holdings, marketData, transactions, fx)
                .getAllReceivedDividendsInfoFromTransactions("p");

        amount("2.5", result.getAmountByMonthByCurrency().get("USD").get("2024-01"));
        amount("4", result.getAmountByMonthByCurrency().get("EUR").get("2024-02"));
        amount("6", result.getProjectionByCurrency().get("USD"));
        assertEquals(Map.of("A", "USD", "B", "EUR"), result.getTickerCurrency());
        assertEquals(2, result.getTickerAmount().size());
        verify(marketRepository).findByTickerInForIncome(argThat(tickers -> sameTickers(tickers, "A", "B")));
        verify(marketRepository, never()).findByTicker(anyString());
        verify(holdings).getAllHoldingsByPortfolioId("p");
        verify(transactions).findAllByPortfolioId("p");
        verify(transactions, never()).findAllByPortfolioIdAndTicker(anyString(), anyString());
    }

    @Test
    void historicalCalendarPreparesEachTickerOnceAndKeepsSoldPositions() {
        when(holdings.getAllHoldingsByPortfolioId("p")).thenReturn(List.of(holding("A", Assets.STOCK, "3")));
        when(transactions.findAllByPortfolioId("p")).thenReturn(List.of(
                trade("A", "2024-02-01", TransactionType.SELL, "2"),
                trade("A", "2024-01-01", TransactionType.BUY, "5"),
                trade("B", "2024-02-01", TransactionType.BUY, "2"),
                trade("B", "2024-03-01", TransactionType.SELL, "2")));
        when(marketRepository.findByTickerInForIncome(any())).thenReturn(List.of(
                market("A", "USD", "100", dividend("2024-01-02", "0.5"), dividend("2024-02-01", "1")),
                market("B", "EUR", "50", dividend("2024-02-02", "2"))));
        DividendUtils utils = spy(new DividendUtils());
        DividendCalendarServiceImpl calendar = calendar(utils);

        var result = calendar.getDividendCalendarByPortfolioId("p", 2024);

        amount("5", result.get("JANUARY").getFirst().getStockQuantity());
        DividendsCalendarData a = result.get("FEBRUARY").stream().filter(row -> row.getTicker().equals("A")).findFirst().orElseThrow();
        amount("5", a.getStockQuantity()); // SELL on the ex-date still collects.
        assertFalse(a.isScheduled());
        assertEquals(2, result.get("FEBRUARY").size());
        verify(utils, times(2)).prepareEntitlements(any(), any());
        verify(utils, never()).getSharesHeldOnDate(any(), any(), any());
        verify(marketRepository).findByTickerInForIncome(argThat(tickers -> sameTickers(tickers, "A", "B")));
        verify(marketRepository, never()).findByTicker(anyString());
    }

    @Test
    void rollingCalendarBatchesAllHeldTickers() {
        when(holdings.getAllHoldingsByPortfolioId("p")).thenReturn(List.of(
                holding("A", Assets.STOCK, "3"), holding("B", Assets.STOCK, "2")));
        String date = LocalDate.now().minusYears(1).withMonth(6).withDayOfMonth(15).toString();
        when(marketRepository.findByTickerInForIncome(any())).thenReturn(List.of(
                market("A", "USD", "100", dividend(date, "1")), market("B", "EUR", "50", dividend(date, "2"))));

        var result = calendar(new DividendUtils()).getDividendCalendarByPortfolioId("p");

        assertEquals(2, result.values().stream().mapToInt(List::size).sum());
        verify(marketRepository).findByTickerInForIncome(argThat(tickers -> sameTickers(tickers, "A", "B")));
        verify(marketRepository, never()).findByTicker(anyString());
    }

    @Test
    void diversificationBatchesMarketAndCustomMetadataWithoutChangingBuckets() {
        when(holdings.getAllHoldingsByPortfolioId("p")).thenReturn(List.of(
                holding("A", Assets.STOCK, "3"), holding("C", Assets.CUSTOM, "2"), holding("D", Assets.CUSTOM, "3")));
        MarketData stock = market("A", "USD", "100");
        stock.setCountry("US");
        stock.setSector("Technology");
        stock.setIndustry("Software");
        when(marketRepository.findByTickerInForDiversification(any())).thenReturn(List.of(
                stock, market("C", "USD", "20"), market("D", "USD", "30")));
        CustomAssetRepository customRepository = mock(CustomAssetRepository.class);
        when(customRepository.findForDiversification(anyString(), any())).thenReturn(List.of(
                custom("C", "SK", "Coin"), custom("D", "DE", "Fund")));
        CustomAssetServiceImpl customService = new CustomAssetServiceImpl();
        ReflectionTestUtils.setField(customService, "customAssetRepository", customRepository);
        DiversificationServiceImpl service = new DiversificationServiceImpl();
        ReflectionTestUtils.setField(service, "holdingService", holdings);
        ReflectionTestUtils.setField(service, "marketDataService", marketData);
        ReflectionTestUtils.setField(service, "customAssetService", customService);

        var result = service.getAllDiversificationInfo("p");

        amount("300", result.getAmountByCountry().get("US"));
        amount("40", result.getAmountByCountry().get("SK"));
        amount("90", result.getAmountBySector().get("Fund"));
        amount("300", result.getAmountByIndustry().get("Software"));
        verify(marketRepository).findByTickerInForDiversification(argThat(tickers -> sameTickers(tickers, "A", "C", "D")));
        verify(customRepository).findForDiversification(org.mockito.ArgumentMatchers.eq("p"),
                argThat(tickers -> sameTickers(tickers, "C", "D")));
        verify(marketRepository, never()).findByTicker(anyString());
        verify(customRepository, never()).findByPortfolioIdAndTicker(anyString(), anyString());
    }

    private DividendCalendarServiceImpl calendar(DividendUtils utils) {
        DividendCalendarServiceImpl service = new DividendCalendarServiceImpl();
        ReflectionTestUtils.setField(service, "holdingService", holdings);
        ReflectionTestUtils.setField(service, "marketDataService", marketData);
        ReflectionTestUtils.setField(service, "transactionService", transactions);
        ReflectionTestUtils.setField(service, "dividendUtils", utils);
        return service;
    }

    private boolean sameTickers(Collection<String> actual, String... expected) {
        return Set.copyOf(actual).equals(Set.of(expected));
    }

    private Holdings holding(String ticker, Assets type, String quantity) {
        Holdings holding = new Holdings();
        holding.setTicker(ticker);
        holding.setAssetType(type);
        holding.setQuantity(new BigDecimal(quantity));
        return holding;
    }

    private Transactions trade(String ticker, String date, TransactionType type, String quantity) {
        Transactions tx = new Transactions();
        tx.setTicker(ticker);
        tx.setDate(LocalDate.parse(date));
        tx.setTransactionType(type);
        tx.setQuantity(new BigDecimal(quantity));
        return tx;
    }

    private Dividend dividend(String date, String value) {
        Dividend dividend = new Dividend();
        dividend.setDividendDate(LocalDate.parse(date));
        dividend.setDividendAmount(new BigDecimal(value));
        return dividend;
    }

    private MarketData market(String ticker, String currency, String price, Dividend... dividends) {
        MarketData md = new MarketData();
        md.setTicker(ticker);
        md.setCurrency(currency);
        md.setPrice(new BigDecimal(price));
        md.setDividends(List.of(dividends));
        md.setLastDividendPayment(BigDecimal.ONE);
        return md;
    }

    private CustomAsset custom(String ticker, String country, String category) {
        CustomAsset custom = new CustomAsset();
        custom.setTicker(ticker);
        custom.setCountry(country);
        custom.setAssetType(category);
        return custom;
    }

    private void amount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
