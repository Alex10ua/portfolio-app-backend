package com.dev.alex.Service.Dividends;

import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.Transactions;
import jakarta.validation.constraints.Null;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
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
        if (exDividendDate == null || transactions == null) return ZERO;

        List<Transactions> entitled = transactions.stream()
                .filter(tx -> tx != null && tx.getDate() != null && tx.getQuantity() != null
                        && tx.getDate().isBefore(exDividendDate))
                .sorted(Comparator.comparing(Transactions::getDate))
                .toList();

        BigDecimal sharesHeld = ZERO;
        for (Transactions tx : entitled) {
            BigDecimal quantity = tx.getQuantity().multiply(splitFactorAfter(splits, tx.getDate()));
            if (tx.getTransactionType() == TransactionType.BUY) {
                sharesHeld = sharesHeld.add(quantity);
            } else if (tx.getTransactionType() == TransactionType.SELL) {
                // oversell (e.g. a partial import missing its BUYs) is clamped, never negative
                sharesHeld = sharesHeld.subtract(quantity).max(ZERO);
            }
        }
        return sharesHeld;
    }

    /** Product of the ratios of every split dated after {@code date}; 1 when none. */
    private static BigDecimal splitFactorAfter(List<Splits> splits, LocalDate date) {
        BigDecimal factor = BigDecimal.ONE;
        if (splits == null) return factor;
        for (Splits split : splits) {
            if (split == null || split.getSplitDate() == null || split.getRatioSplit() == null
                    || split.getRatioSplit().signum() <= 0) continue;
            if (date.isBefore(split.getSplitDate())) {
                factor = factor.multiply(split.getRatioSplit());
            }
        }
        return factor;
    }

    /**
     * Calculates the total received dividends for a single stock based on its transaction history,
     * market dividend announcements, and stock splits.
     * Transactions and splits may come in any order.
     */
    public BigDecimal calculateAllDividendsByStockAuto(List<Dividend> dividendList,
                                                       List<Transactions> sortedTransactionsList,
                                                       List<Splits> sortedSplitsList) {
        if (dividendList == null || dividendList.isEmpty()) {
            return ZERO;
        }

        BigDecimal totalDividendAmount = ZERO;

        for (Dividend dividend : dividendList) {
            LocalDate exDividendDate = dividend.getDividendDate(); // CRITICAL: This MUST be the ex-dividend date

            BigDecimal sharesHeldOnExDate = getSharesHeldOnDate(exDividendDate, sortedTransactionsList, sortedSplitsList);

            if (sharesHeldOnExDate.compareTo(ZERO) > 0) {
                BigDecimal dividendReceived = sharesHeldOnExDate.multiply(dividend.getDividendAmount());
                totalDividendAmount = totalDividendAmount.add(dividendReceived);
            }
        }
        // Consider scaling the final result if needed, e.g., .setScale(2, RoundingMode.HALF_EVEN)
        return totalDividendAmount;
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

            for (Dividend dividend : dividendList) {
                LocalDate exDividendDate = dividend.getDividendDate(); // Ex-dividend date

                BigDecimal sharesHeldOnExDate = getSharesHeldOnDate(exDividendDate, stockTransactions, stockSplits);

                if (sharesHeldOnExDate.compareTo(ZERO) > 0) {
                    BigDecimal dividendReceivedForThisInstance = sharesHeldOnExDate.multiply(dividend.getDividendAmount());
                    if (divisor.compareTo(BigDecimal.ONE) != 0) {
                        dividendReceivedForThisInstance = dividendReceivedForThisInstance
                                .divide(divisor, 10, RoundingMode.HALF_UP);
                    }
                    String monthKey = exDividendDate.format(YEAR_MONTH_FORMATTER);
                    monthDividendsMap.merge(monthKey, dividendReceivedForThisInstance, BigDecimal::add);
                }
            }
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
