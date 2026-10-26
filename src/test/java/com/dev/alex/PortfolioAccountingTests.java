package com.dev.alex;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Service.PortfolioAccounting;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioAccountingTests {
    private final PortfolioAccounting accounting = new PortfolioAccounting();
    private final LocalDate today = LocalDate.of(2026, 10, 25);

    @Test
    void fifoOpenCostAndRealizedGainReconcileAfterPartialSale() {
        var result = calculate(List.of(trade("BUY", "1", "100", "0", "2025-01-01"),
                trade("BUY", "1", "200", "0", "2025-02-01"),
                trade("SELL", "1", "300", "0", "2025-03-01")));
        amount("200", result.positions().get("TEST").costBasis());
        amount("200", result.realizedPnLByCurrency().get("USD"));
        BigDecimal remainingValue = new BigDecimal("300");
        amount("300", remainingValue.subtract(result.positions().get("TEST").costBasis())
                .add(result.realizedPnLByCurrency().get("USD")));
    }

    @Test
    void allocatesPurchaseFeesBetweenRemainingBasisAndRealizedGains() {
        var result = calculate(List.of(trade("BUY", "10", "100", "10", "2025-01-01"),
                trade("SELL", "4", "120", "4", "2025-02-01")));
        var open = result.positions().get("TEST");
        amount("6", open.quantity());
        amount("606", open.costBasis());
        amount("6", open.commissions());
        amount("72", result.realizedPnLByCurrency().get("USD"));
        amount("14", result.commissionsByCurrency().get("USD"));
        amount("8", result.realizedCommissionsByCurrency().get("USD"));
    }

    @Test
    void appliesOnlySplitsEffectiveByValuationDateAndPreservesMoney() {
        List<Transactions> transactions = List.of(trade("BUY", "10", "100", "10", "2025-01-01"),
                trade("SELL", "5", "60", "5", "2025-03-01"));
        Map<String, List<Splits>> splits = Map.of("TEST", List.of(split("2025-02-01", "2"),
                split("2027-01-01", "4")));
        var before = accounting.calculate(transactions, splits, LocalDate.of(2025, 1, 31));
        amount("10", before.positions().get("TEST").quantity());
        amount("1010", before.positions().get("TEST").costBasis());
        var after = accounting.calculate(transactions, splits, today);
        amount("15", after.positions().get("TEST").quantity());
        amount("757.5", after.positions().get("TEST").costBasis());
        amount("7.5", after.positions().get("TEST").commissions());
        amount("42.5", after.realizedPnLByCurrency().get("USD"));
    }

    @Test
    void treatsTradesOnSplitDayAsAlreadyInNewShareBasis() {
        var result = accounting.calculate(List.of(trade("BUY", "10", "100", "0", "2025-01-01"),
                        trade("BUY", "5", "50", "0", "2025-02-01")),
                Map.of("TEST", List.of(split("2025-02-01", "2"))), today);
        amount("25", result.positions().get("TEST").quantity());
        amount("1250", result.positions().get("TEST").costBasis());
    }

    @Test
    void closesReverseSplitLotsWithoutRoundingDust() {
        var result = accounting.calculate(List.of(trade("BUY", "10", "100", "0", "2025-01-01"),
                        trade("SELL", "1", "1200", "0", "2025-03-01")),
                Map.of("TEST", List.of(split("2025-02-01", "0.1"))), today);
        assertTrue(result.positions().isEmpty());
        amount("200", result.realizedPnLByCurrency().get("USD"));
    }

    @Test
    void consumesTinyLotOnceAndClampsOversell() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            var result = calculate(List.of(trade("BUY", "0.000000001", "100", "0", "2025-01-01"),
                    trade("SELL", "1", "150", "0", "2025-02-01")));
            assertTrue(result.positions().isEmpty());
            amount("0.00000005", result.realizedPnLByCurrency().get("USD"));
        });
        var tiny = calculate(List.of(trade("BUY", "0.000000000001", "100", "0", "2025-01-01")));
        amount("0.000000000001", tiny.positions().get("TEST").quantity());
    }

    @Test
    void filtersNonTradesAndMalformedRowsBeforeSplitArithmetic() {
        Transactions dividend = new Transactions();
        dividend.setTicker("TEST");
        dividend.setTransactionType(TransactionType.DIVIDEND);
        dividend.setDate(LocalDate.of(2025, 1, 2));
        Transactions invalid = trade("BUY", "-1", "100", "0", "2025-01-01");
        Transactions noDate = trade("BUY", "1", "100", "0", "2025-01-01");
        noDate.setDate(null);
        var result = accounting.calculate(List.of(dividend, invalid, noDate,
                        trade("BUY", "2", "100", "0", "2025-01-01")),
                Map.of("TEST", List.of(split("2025-02-01", "2"))), today);
        amount("4", result.positions().get("TEST").quantity());
        amount("200", result.positions().get("TEST").costBasis());
    }

    @Test
    void ignoresMarketSplitsForCustomAssets() {
        Transactions custom = trade("BUY", "2", "100", "0", "2025-01-01");
        custom.setAssetType(Assets.CUSTOM);
        var result = accounting.calculate(List.of(custom), Map.of("TEST", List.of(split("2025-02-01", "2"))), today);
        amount("2", result.positions().get("TEST").quantity());
    }

    @Test
    void retainsBuyAndSellCurrenciesInsteadOfSubtractingUnlikeMoney() {
        Transactions euroBuy = trade("BUY", "1", "100", "1", "2025-01-01");
        euroBuy.setCurrency("EUR");
        Transactions dollarBuy = trade("BUY", "1", "200", "2", "2025-02-01");
        var mixed = calculate(List.of(euroBuy, dollarBuy)).positions().get("TEST");
        assertNull(mixed.currency());
        assertNull(mixed.costBasis());
        amount("101", mixed.costBasisByCurrency().get("EUR"));
        amount("202", mixed.costBasisByCurrency().get("USD"));
        var sold = calculate(List.of(euroBuy, dollarBuy, trade("SELL", "1", "150", "1", "2025-03-01")));
        amount("-101", sold.realizedPnLByCurrency().get("EUR"));
        amount("149", sold.realizedPnLByCurrency().get("USD"));
        amount("202", sold.positions().get("TEST").costBasis());
        amount("2", sold.positions().get("TEST").commissions());
    }

    @Test
    void conservesFeesAcrossRepeatingDecimalPartialLotAllocations() {
        var result = calculate(List.of(trade("BUY", "3", "100", "1", "2025-01-01"),
                trade("SELL", "1", "120", "1", "2025-02-01"),
                trade("SELL", "1", "120", "1", "2025-03-01"),
                trade("SELL", "1", "120", "1", "2025-04-01")));
        assertTrue(result.positions().isEmpty());
        amount("56", result.realizedPnLByCurrency().get("USD"));
        amount("4", result.realizedCommissionsByCurrency().get("USD"));
    }

    @Test
    void sortsUnorderedImportsAndExcludesFutureTrades() {
        var result = calculate(List.of(trade("SELL", "1", "150", "0", "2025-02-01"),
                trade("BUY", "5", "10", "2", "2027-01-01"),
                trade("BUY", "2", "100", "0", "2025-01-01")));
        amount("1", result.positions().get("TEST").quantity());
        amount("100", result.positions().get("TEST").costBasis());
        amount("50", result.realizedPnLByCurrency().get("USD"));
        assertTrue(result.commissionsByCurrency().isEmpty());
    }

    private PortfolioAccounting.Result calculate(List<Transactions> transactions) {
        return accounting.calculate(transactions, Map.of(), today);
    }

    private Transactions trade(String type, String quantity, String price, String fee, String date) {
        Transactions tx = new Transactions();
        tx.setTicker("TEST");
        tx.setAssetType(Assets.STOCK);
        tx.setTransactionType(TransactionType.valueOf(type));
        tx.setQuantity(new BigDecimal(quantity));
        tx.setPrice(new BigDecimal(price));
        tx.setCommission(new BigDecimal(fee));
        tx.setCurrency("USD");
        tx.setDate(LocalDate.parse(date));
        return tx;
    }

    private Splits split(String date, String ratio) {
        return new Splits(LocalDate.parse(date), new BigDecimal(ratio));
    }

    private void amount(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "Expected " + expected + " but got " + actual);
    }
}
