package com.dev.alex.Service.Dividends;

import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.Transactions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DividendUtilsTest {
    private final DividendUtils utils = new DividendUtils();

    @Test
    void sameDayTradeEnteredSellFirstLeavesNoShares() {
        var timeline = utils.prepareEntitlements(List.of(
                trade("2024-05-10", TransactionType.SELL, "5"),
                trade("2024-05-10", TransactionType.BUY, "5")), List.of());

        amount("0", timeline.sharesBefore(LocalDate.parse("2024-05-11")));
    }

    @Test
    void excludesBothKindsOfTradeOnTheExDate() {
        var timeline = utils.prepareEntitlements(List.of(
                trade("2024-05-10", TransactionType.BUY, "7"),
                trade("2024-05-09", TransactionType.BUY, "10"),
                trade("2024-05-10", TransactionType.SELL, "4")), List.of());

        amount("0", timeline.sharesBefore(LocalDate.parse("2024-05-09")));
        amount("10", timeline.sharesBefore(LocalDate.parse("2024-05-10")));
        amount("13", timeline.sharesBefore(LocalDate.parse("2024-05-11")));
    }

    @Test
    void includesSplitsAfterTheExDateButNotOnATradesOwnDate() {
        var timeline = utils.prepareEntitlements(List.of(
                trade("2021-06-01", TransactionType.BUY, "3"),
                trade("2020-01-01", TransactionType.BUY, "2"),
                trade("2022-01-01", TransactionType.SELL, "1")), List.of(
                split("2024-06-01", "10"), split("2021-06-01", "4")));

        amount("80", timeline.sharesBefore(LocalDate.parse("2020-02-01")));
        amount("80", timeline.sharesBefore(LocalDate.parse("2021-06-01")));
        amount("110", timeline.sharesBefore(LocalDate.parse("2021-06-02")));
        amount("100", timeline.sharesBefore(LocalDate.parse("2022-01-02")));
    }

    @Test
    void clampsEachOversellBeforeLaterBuys() {
        var timeline = utils.prepareEntitlements(List.of(
                trade("2024-04-01", TransactionType.BUY, "2"),
                trade("2024-03-01", TransactionType.SELL, "10"),
                trade("2024-02-01", TransactionType.BUY, "3"),
                trade("2024-01-01", TransactionType.SELL, "5")), null);

        amount("0", timeline.sharesBefore(LocalDate.parse("2024-01-02")));
        amount("3", timeline.sharesBefore(LocalDate.parse("2024-02-02")));
        amount("0", timeline.sharesBefore(LocalDate.parse("2024-03-02")));
        amount("2", timeline.sharesBefore(LocalDate.parse("2024-04-02")));
    }

    @Test
    void suppliesTickerTotalAndMonthlyIncomeFromTheSameEntitlements() {
        var totals = utils.calculateDividends(List.of(
                dividend("2024-02-02", "0.5"), dividend("2024-01-02", "0.25"),
                dividend("2024-02-01", "0.5")), List.of(
                trade("2024-02-01", TransactionType.SELL, "2"),
                trade("2024-01-01", TransactionType.BUY, "5")), List.of(split("2024-06-01", "2")));

        amount("10.5", totals.total());
        amount("2.5", totals.byMonth().get("2024-01"));
        amount("8", totals.byMonth().get("2024-02"));
        assertEquals(2, totals.byMonth().size());
    }

    @Test
    void closingSaleKeepsItsExDateDividendAndStopsLaterIncomeUntilReopening() {
        Transactions close = trade("2025-03-01", TransactionType.SELL, "1");
        close.setClosesPosition(true);
        List<Transactions> trades = List.of(trade("2025-01-01", TransactionType.BUY, "1"), close,
                trade("2025-05-01", TransactionType.BUY, "2"));
        List<Splits> splits = List.of(split("2025-02-01", "1.02"), split("2025-04-01", "1.01"));
        var timeline = utils.prepareEntitlements(trades, splits);

        amount("1.0302", timeline.sharesBefore(LocalDate.parse("2025-03-01")));
        amount("0", timeline.sharesBefore(LocalDate.parse("2025-03-02")));
        amount("0", timeline.sharesBefore(LocalDate.parse("2025-04-02")));
        amount("0", timeline.sharesBefore(LocalDate.parse("2025-05-01")));
        amount("2", timeline.sharesBefore(LocalDate.parse("2025-05-02")));

        var totals = utils.calculateDividends(List.of(dividend("2025-03-01", "1"),
                dividend("2025-04-01", "1"), dividend("2025-05-02", "1")), trades, splits);
        amount("3.0302", totals.total());
        assertEquals(2, totals.byMonth().size());
    }

    @Test
    void preparedTimelineMatchesIndependentReplayForUnsortedHistory() {
        Random random = new Random(7391);
        LocalDate start = LocalDate.of(2015, 1, 1);
        List<Transactions> trades = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            trades.add(trade(start.plusDays(random.nextInt(1500)).toString(),
                    random.nextBoolean() ? TransactionType.BUY : TransactionType.SELL,
                    BigDecimal.valueOf(random.nextInt(100), 1).toPlainString()));
        }
        List<Splits> splits = new ArrayList<>();
        String[] ratios = {"0.2", "0.5", "2", "10"};
        for (int i = 0; i < 20; i++) {
            splits.add(split(start.plusDays(random.nextInt(1800)).toString(), ratios[random.nextInt(ratios.length)]));
        }
        Collections.shuffle(trades, random);
        Collections.shuffle(splits, random);
        var timeline = utils.prepareEntitlements(trades, splits);
        for (int day = 0; day <= 2000; day += 7) {
            LocalDate exDate = start.plusDays(day);
            assertEquals(0, replay(exDate, trades, splits).compareTo(timeline.sharesBefore(exDate)), exDate.toString());
        }
    }

    /**
     * Brute-force reference. Within a day, trades keep their recorded order except that a SELL
     * needing more shares than are held waits for that day's remaining BUYs.
     */
    private BigDecimal replay(LocalDate exDate, List<Transactions> trades, List<Splits> splits) {
        BigDecimal shares = BigDecimal.ZERO;
        java.util.Map<LocalDate, List<Transactions>> byDay = new java.util.TreeMap<>();
        for (Transactions tx : trades.stream().filter(t -> t.getDate().isBefore(exDate))
                .sorted(Comparator.comparing(Transactions::getDate)).toList()) {
            byDay.computeIfAbsent(tx.getDate(), d -> new ArrayList<>()).add(tx);
        }
        for (List<Transactions> day : byDay.values()) {
            List<Transactions> pending = new ArrayList<>(day);
            while (!pending.isEmpty()) {
                Transactions next = null;
                for (Transactions tx : pending) {
                    boolean buyPending = pending.stream()
                            .anyMatch(o -> o != tx && o.getTransactionType() == TransactionType.BUY);
                    if (tx.getTransactionType() == TransactionType.SELL && buyPending
                            && adjusted(tx, splits).compareTo(shares) > 0) continue;
                    next = tx;
                    break;
                }
                Transactions chosen = next;
                pending.removeIf(o -> o == chosen);
                BigDecimal quantity = adjusted(chosen, splits);
                shares = chosen.getTransactionType() == TransactionType.BUY
                        ? shares.add(quantity) : shares.subtract(quantity).max(BigDecimal.ZERO);
            }
        }
        return shares;
    }

    private BigDecimal adjusted(Transactions tx, List<Splits> splits) {
        BigDecimal quantity = tx.getQuantity();
        for (Splits split : splits) {
            if (tx.getDate().isBefore(split.getSplitDate())) quantity = quantity.multiply(split.getRatioSplit());
        }
        return quantity;
    }

    private Transactions trade(String date, TransactionType type, String quantity) {
        Transactions tx = new Transactions();
        tx.setDate(LocalDate.parse(date));
        tx.setTransactionType(type);
        tx.setQuantity(new BigDecimal(quantity));
        return tx;
    }

    private Splits split(String date, String ratio) {
        Splits split = new Splits();
        split.setSplitDate(LocalDate.parse(date));
        split.setRatioSplit(new BigDecimal(ratio));
        return split;
    }

    private Dividend dividend(String date, String value) {
        Dividend dividend = new Dividend();
        dividend.setDividendDate(LocalDate.parse(date));
        dividend.setDividendAmount(new BigDecimal(value));
        return dividend;
    }

    private void amount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
