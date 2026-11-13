package com.dev.alex.Service.Ai;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Repository.SharesOutstandingHistoryRepository;
import com.dev.alex.Service.DividendsServiceImpl;
import com.dev.alex.Service.HoldingServiceImpl;
import com.dev.alex.Service.Interface.FxRateService;
import com.dev.alex.Service.MarketDataServiceImpl;
import com.dev.alex.Service.TransactionServiceImpl;
import com.dev.alex.Service.WatchlistServiceImpl;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiDividendIncomeTests {
    private MappingMongoConverter converter;

    @BeforeEach
    void setUp() throws Exception {
        MongoCustomConversions conversions = MongoCustomConversions.create(adapter -> {});
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
    }

    @Test
    void nonFiniteDividendAmountsRemainMissingInsteadOfBreakingTheMarketDocument() {
        for (Object invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Float.NaN, Float.POSITIVE_INFINITY, Decimal128.NaN, Decimal128.POSITIVE_INFINITY,
                Decimal128.NEGATIVE_INFINITY)) {
            MarketData market = converter.read(MarketData.class, new Document("ticker", "TEST")
                    .append("dividends", List.of(payment("2024-02-01", "0.25"), payment("2024-03-01", invalid))));

            assertEquals(new BigDecimal("0.25"), market.getDividends().getFirst().getDividendAmount());
            assertNull(market.getDividends().getLast().getDividendAmount());
            assertEquals(LocalDate.of(2024, 3, 1), market.getDividends().getLast().getDividendDate());
        }
    }

    @Test
    void finiteStoredRepresentationsAndMissingAmountsKeepTheirMeaning() {
        for (Object value : List.of("0.125", 0.125d, 0.125f, new Decimal128(new BigDecimal("0.125")))) {
            assertEquals(0, new BigDecimal("0.125").compareTo(
                    converter.read(Dividend.class, payment("2024-02-01", value)).getDividendAmount()));
        }
        assertEquals(0, BigDecimal.ZERO.compareTo(
                converter.read(Dividend.class, payment("2024-02-01", 0)).getDividendAmount()));
        assertNull(converter.read(Dividend.class, payment("2024-02-01", null)).getDividendAmount());
        assertNull(converter.read(Dividend.class, new Document("dividendDate", "2024-02-01")).getDividendAmount());
    }

    @Test
    void finiteAmountsKeepTheDefaultWriteRepresentationAndPrecision() {
        BigDecimal amount = new BigDecimal("0.1234567890123456789012345678");
        Dividend dividend = new Dividend(LocalDate.of(2024, 2, 1), amount, null);
        Document stored = new Document();
        converter.write(dividend, stored);

        assertEquals(converter.convertToMongoType(amount), stored.get("dividendAmount"));
        assertEquals(amount, converter.read(Dividend.class, stored).getDividendAmount());
    }

    @Test
    void unrelatedMoneyFieldsStillRejectInvalidValues() {
        assertThrows(ConversionFailedException.class,
                () -> converter.read(MarketData.class, new Document("price", Double.NaN)));
    }

    @Test
    void incomeKeepsValidPaymentsIncludingSoldPositionsAndSeparateCurrencies() {
        MarketData sold = converter.read(MarketData.class, new Document("ticker", "SOLD")
                .append("currency", "USD").append("dividends", List.of(
                        payment("2024-02-01", 0.5d), payment("2024-03-01", Double.NaN))));
        MarketData held = converter.read(MarketData.class, new Document("ticker", "HELD")
                .append("currency", "EUR").append("yearlyDividend", "2")
                .append("dividends", List.of(payment("2024-04-01", "1"))));
        HoldingServiceImpl holdings = mock(HoldingServiceImpl.class);
        Holdings holding = new Holdings();
        holding.setTicker("HELD");
        holding.setAssetType(Assets.STOCK);
        holding.setQuantity(new BigDecimal("3"));
        when(holdings.getAllHoldingsByPortfolioId("p")).thenReturn(List.of(holding));
        MarketDataServiceImpl market = mock(MarketDataServiceImpl.class);
        when(market.getMarketDataForIncome(any())).thenReturn(Map.of("SOLD", sold, "HELD", held));
        TransactionServiceImpl transactions = mock(TransactionServiceImpl.class);
        when(transactions.findAllByPortfolioId("p")).thenReturn(List.of(
                trade("SOLD", "2024-01-01", TransactionType.BUY, "2"),
                trade("SOLD", "2024-04-15", TransactionType.SELL, "2"),
                trade("HELD", "2024-01-01", TransactionType.BUY, "3")));
        FxRateService fx = mock(FxRateService.class);
        when(fx.getAllRatesAsMap()).thenReturn(Map.of("EUR", BigDecimal.ONE, "USD", new BigDecimal("1.1")));
        AiIncomeAssembler assembler = new AiIncomeAssembler();
        ReflectionTestUtils.setField(assembler, "dividendsService",
                new DividendsServiceImpl(holdings, market, transactions, fx));

        var income = assembler.dividends("p");

        assertEquals(0, BigDecimal.ONE.compareTo(income.monthlyByCurrency().get("USD").get("2024-02")));
        assertEquals(0, BigDecimal.ONE.compareTo(income.quarterlyByCurrency().get("USD").get("2024-Q1")));
        assertEquals(0, new BigDecimal("3").compareTo(income.yearlyByCurrency().get("EUR").get("2024")));
        assertEquals(0, new BigDecimal("6").compareTo(income.forwardProjectionByCurrency().get("EUR")));
        assertFalse(income.monthlyByCurrency().get("USD").containsKey("2024-03"));
        assertEquals(2, income.receivedByTicker().size());
    }

    @Test
    void missingAmountsDoNotBecomeHistoricalZeroPaymentsOrExtraPaymentFrequency() {
        LocalDate today = LocalDate.now();
        MarketData market = converter.read(MarketData.class, new Document("ticker", "TEST")
                .append("dividends", List.of(payment(today.minusMonths(3).toString(), "0.25"),
                        payment(today.minusMonths(2).toString(), Double.NaN),
                        payment(today.minusMonths(1).toString(), "0.5"))));
        MarketDataRepository repository = mock(MarketDataRepository.class);
        when(repository.findByTicker("TEST")).thenReturn(market);
        SharesOutstandingHistoryRepository shares = mock(SharesOutstandingHistoryRepository.class);
        when(shares.findById("TEST")).thenReturn(Optional.empty());
        MarketDataServiceImpl service = new MarketDataServiceImpl();
        ReflectionTestUtils.setField(service, "marketDataRepository", repository);
        ReflectionTestUtils.setField(service, "sharesHistoryRepository", shares);

        var history = service.getHistoricalData("TEST");

        assertEquals(List.of(new BigDecimal("0.25"), new BigDecimal("0.5")),
                history.getDividends().stream().map(Dividend::getDividendAmount).toList());
        assertEquals("Semi-annual", ReflectionTestUtils.invokeMethod(
                new WatchlistServiceImpl(), "frequency", market.getDividends()));
    }

    private Document payment(String date, Object amount) {
        return new Document("dividendDate", date).append("dividendAmount", amount);
    }

    private Transactions trade(String ticker, String date, TransactionType type, String quantity) {
        Transactions transaction = new Transactions();
        transaction.setTicker(ticker);
        transaction.setDate(LocalDate.parse(date));
        transaction.setTransactionType(type);
        transaction.setQuantity(new BigDecimal(quantity));
        return transaction;
    }
}
