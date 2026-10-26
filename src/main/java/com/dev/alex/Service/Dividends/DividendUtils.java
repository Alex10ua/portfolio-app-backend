package com.dev.alex.Service.Dividends;

import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.Transactions;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class DividendUtils {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * Shares entitled to the dividend with the given ex-date, in today's share basis.
     *
     * <p>Entitlement: only trades dated before the ex-date count. A BUY on the ex-date buys the
     * share without the dividend and a SELL on the ex-date still collects it — the previous
     * on-or-before test had both backwards.
     *
     * <p>Basis: stored dividend amounts are split-adjusted to today — Yahoo back-scales every
     * pre-split payment (NVDA's $0.04 of Dec 2023 is stored as 0.004). The share count has to be
     * in the same basis, so each trade is multiplied by every split dated after it, whether that
     * split falls before or after the ex-date. Counting shares in the ex-date's own basis made
     * every dividend paid before a later split read smaller by the split factor.
     *
     * <p>A trade dated on a split day is already post-split (a Yahoo split date is the first
     * session that trades split-adjusted), the same rule as HoldingServiceImpl.accumulate.
     *
     * @param exDividendDate the dividend's ex-date
     * @param transactions   the ticker's transactions, in any order
     * @param splits         the ticker's splits, in any order
     */
    public BigDecimal getSharesHeldOnDate(LocalDate exDividendDate,
                                          List<Transactions> transactions,
                                          List<Splits> splits) {
        if (exDividendDate == null) return ZERO;
        return prepareEntitlements(transactions, splits).sharesBefore(exDividendDate);
    }

    /**
     * Prepares one ticker once, so every dividend date can use a prefix balance rather
     * than replaying its trades. Split suffixes keep each trade in today's share basis,
     * including splits after the dividend date; no division or intermediate rounding.
     */
    public ShareTimeline prepareEntitlements(List<Transactions> transactions, List<Splits> splits) {
        NavigableMap<LocalDate, BigDecimal> splitRatios = new TreeMap<>();
        if (splits != null) {
            for (Splits split : splits) {
                if (split == null || split.getSplitDate() == null || split.getRatioSplit() == null
                        || split.getRatioSplit().signum() <= 0) continue;
                splitRatios.merge(split.getSplitDate(), split.getRatioSplit(), BigDecimal::multiply);
            }
        }
        NavigableMap<LocalDate, BigDecimal> suffixFactors = new TreeMap<>();
        BigDecimal factor = BigDecimal.ONE;
        for (Map.Entry<LocalDate, BigDecimal> split : splitRatios.descendingMap().entrySet()) {
            factor = factor.multiply(split.getValue());
            suffixFactors.put(split.getKey(), factor);
        }

        List<Transactions> ordered = transactions == null ? List.of() : transactions.stream()
                .filter(tx -> tx != null && tx.getDate() != null && tx.getQuantity() != null
                        && (tx.getTransactionType() == TransactionType.BUY
                            || tx.getTransactionType() == TransactionType.SELL))
                .sorted(Comparator.comparing(Transactions::getDate))
                .toList();
        NavigableMap<LocalDate, BigDecimal> balances = new TreeMap<>();
        BigDecimal sharesHeld = ZERO;
        for (Transactions tx : ordered) {
            // A trade on the split date is already post-split: use strictly later splits.
            Map.Entry<LocalDate, BigDecimal> suffix = suffixFactors.higherEntry(tx.getDate());
            BigDecimal quantity = tx.getQuantity().multiply(suffix == null ? BigDecimal.ONE : suffix.getValue());
            if (tx.getTransactionType() == TransactionType.BUY) {
                sharesHeld = sharesHeld.add(quantity);
            } else {
                // oversell (e.g. a partial import missing its BUYs) is clamped, never negative
                sharesHeld = sharesHeld.subtract(quantity).max(ZERO);
            }
            balances.put(tx.getDate(), sharesHeld);
        }
        return new ShareTimeline(balances);
    }

    public static final class ShareTimeline {
        private final NavigableMap<LocalDate, BigDecimal> balances;

        private ShareTimeline(NavigableMap<LocalDate, BigDecimal> balances) {
            this.balances = balances;
        }

        public BigDecimal sharesBefore(LocalDate exDividendDate) {
            if (exDividendDate == null) return ZERO;
            // Both BUY and SELL on the ex-date leave the prior closing balance entitled.
            Map.Entry<LocalDate, BigDecimal> balance = balances.lowerEntry(exDividendDate);
            return balance == null ? ZERO : balance.getValue();
        }
    }

    public record DividendTotals(BigDecimal total, Map<String, BigDecimal> byMonth) {
    }

    /** A single entitlement pass supplies both ticker totals and the monthly income series. */
    public DividendTotals calculateDividends(List<Dividend> dividends,
                                             List<Transactions> transactions, List<Splits> splits) {
        return calculateDividends(dividends, prepareEntitlements(transactions, splits), BigDecimal.ONE);
    }

    private DividendTotals calculateDividends(List<Dividend> dividends, ShareTimeline shares, BigDecimal divisor) {
        BigDecimal total = ZERO;
        Map<String, BigDecimal> byMonth = new TreeMap<>();
        if (dividends == null) return new DividendTotals(total, byMonth);
        for (Dividend dividend : dividends) {
            if (dividend == null || dividend.getDividendDate() == null || dividend.getDividendAmount() == null) continue;
            BigDecimal quantity = shares.sharesBefore(dividend.getDividendDate());
            if (quantity.signum() <= 0) continue;
            BigDecimal amount = quantity.multiply(dividend.getDividendAmount());
            if (divisor.compareTo(BigDecimal.ONE) != 0) {
                amount = amount.divide(divisor, 10, RoundingMode.HALF_UP);
            }
            total = total.add(amount);
            byMonth.merge(dividend.getDividendDate().format(YEAR_MONTH_FORMATTER), amount, BigDecimal::add);
        }
        return new DividendTotals(total, byMonth);
    }

    /**
     * Calculates the total received dividends for a single stock based on its transaction history,
     * market dividend announcements, and stock splits.
     * Transactions and splits may come in any order.
     */
    public BigDecimal calculateAllDividendsByStockAuto(List<Dividend> dividendList,
                                                       List<Transactions> sortedTransactionsList,
                                                       List<Splits> sortedSplitsList) {
        return calculateDividends(dividendList, sortedTransactionsList, sortedSplitsList).total();
    }

    /**
     * Calculates received dividends aggregated per month for a set of tickers.
     * Relies on accurate historical transaction data, market dividends, and splits for each ticker.
     * Transactions and splits may come in any order.
     */
    public Map<String, BigDecimal> calculateDividendsPerMonthAuto(
            Map<String, List<Transactions>> transactionsByTicker,
            Map<String, List<Dividend>> marketDividendsByTicker,
            Map<String, List<Splits>> marketSplitsByTicker,
            List<String> tickersToProcess) {
        return calculateDividendsPerMonthAuto(transactionsByTicker, marketDividendsByTicker,
                marketSplitsByTicker, tickersToProcess, Collections.emptyMap());
    }

    /**
     * Same as above but scales each ticker's per-instance amount by a divisor
     * (e.g. 100 for GBp/GBx pence-quoted stocks so the result is in major units).
     */
    public Map<String, BigDecimal> calculateDividendsPerMonthAuto(
            Map<String, List<Transactions>> transactionsByTicker,
            Map<String, List<Dividend>> marketDividendsByTicker,
            Map<String, List<Splits>> marketSplitsByTicker,
            List<String> tickersToProcess,
            Map<String, BigDecimal> tickerDivisor) {

        Map<String, BigDecimal> monthDividendsMap = new TreeMap<>(); // TreeMap to keep months sorted

        for (String ticker : tickersToProcess) {
            List<Dividend> dividendList = marketDividendsByTicker.get(ticker);
            List<Transactions> stockTransactions = transactionsByTicker.getOrDefault(ticker, Collections.emptyList());
            List<Splits> stockSplits = marketSplitsByTicker.getOrDefault(ticker, Collections.emptyList());
            BigDecimal divisor = tickerDivisor.getOrDefault(ticker, BigDecimal.ONE);

            if (dividendList == null || dividendList.isEmpty()) {
                continue;
            }

            calculateDividends(dividendList, prepareEntitlements(stockTransactions, stockSplits), divisor)
                    .byMonth().forEach((month, amount) -> monthDividendsMap.merge(month, amount, BigDecimal::add));
        }
        return monthDividendsMap;
    }

    public BigDecimal calculateAllDividendsByStock(List<Transactions> dividendTransactionsList){
        BigDecimal totalAmount = ZERO;
        if (dividendTransactionsList == null) return totalAmount;
        for (Transactions transaction : dividendTransactionsList){
            // Assuming these are actual dividend payment transactions
            if (transaction.getTotalAmount() != null) { // Or getAmount()
                totalAmount = totalAmount.add(transaction.getTotalAmount());
            }
        }
        return totalAmount;
    }

    public Map<String, BigDecimal> calculateDividendsPerMonth(List<Transactions> dividendTransactionsList){
        Map<String, BigDecimal> monthDividendsMap = new TreeMap<>();
        if (dividendTransactionsList == null) return monthDividendsMap;

        for (Transactions transaction: dividendTransactionsList){
            if (transaction.getDate() != null && transaction.getAmount() != null) {
                String monthKey = transaction.getDate().format(YEAR_MONTH_FORMATTER);
                monthDividendsMap.merge(monthKey, transaction.getAmount(), BigDecimal::add);
            }
        }
        return monthDividendsMap;
    }

}
