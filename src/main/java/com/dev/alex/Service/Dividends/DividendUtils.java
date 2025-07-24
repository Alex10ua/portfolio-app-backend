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
     * Calculates the number of shares held on a specific target date,
     * considering all transactions and splits up to that date.
     *
     * @param targetDate The date for which to calculate shareholding (typically ex-dividend date).
     * @param sortedTransactions A list of transactions for the stock, sorted chronologically by date.
     * @param sortedSplits A list of splits for the stock, sorted chronologically by date.
     * @return The number of shares held on the targetDate.
     */
    private BigDecimal getSharesHeldOnDate(LocalDate targetDate,
                                           List<Transactions> sortedTransactions,
                                           List<Splits> sortedSplits) {
        BigDecimal sharesHeld = BigDecimal.ZERO;

        // Create a combined list of events (transactions and splits)
        List<Object> events = new ArrayList<>();
        if (sortedTransactions != null) events.addAll(sortedTransactions);
        if (sortedSplits != null) events.addAll(sortedSplits);

        // Sort all events chronologically
        events.sort(Comparator.comparing(event -> {
            if (event instanceof Transactions) {
                return ((Transactions) event).getDate();
            } else if (event instanceof Splits) {
                return ((Splits) event).getSplitDate();
            }
            throw new IllegalArgumentException("Unknown event type in timeline");
        }));

        for (Object event : events) {
            if (event instanceof Transactions) {
                Transactions tx = (Transactions) event;
                // Process transaction only if it's on or before the targetDate
                if (tx.getDate() != null && targetDate != null && !tx.getDate().isAfter(targetDate)) {
                    if (tx.getTransactionType() == TransactionType.BUY) {
                        sharesHeld = sharesHeld.add(tx.getQuantity());
                    } else if (tx.getTransactionType() == TransactionType.SELL) {
                        sharesHeld = sharesHeld.subtract(tx.getQuantity());
                        if (sharesHeld.compareTo(ZERO) < 0) {
                            // This indicates an issue, perhaps selling more than owned.
                            System.err.println("Warning: Shares sold resulted in negative balance for ticker before/on " + targetDate + ". Capping at zero.");
                            sharesHeld = ZERO; // throw new IllegalStateException("Oversold stock " + tx.getTicker());
                        }
                    }
                } else {
                    // Transaction is after targetDate, no need to process further transactions
                    // as events are sorted
                    break;
                }
            } else if (event instanceof Splits) {
                Splits split = (Splits) event;
                // Apply split only if it's on or before the targetDate
                if (split.getSplitDate() != null && targetDate != null && !split.getSplitDate().isAfter(targetDate)) {
                    if (sharesHeld.compareTo(ZERO) != 0) {
                        sharesHeld = sharesHeld.multiply(split.getRatioSplit()).setScale(6, RoundingMode.HALF_UP);
                    }
                } else {
                    // Split is after targetDate, no need to process further splits in a sorted list
                    // (though transactions might still be relevant if they are before targetDate and after this split)
                }
            }
        }
        return sharesHeld.compareTo(ZERO) > 0 ? sharesHeld : ZERO; // Ensure non-negative result
    }

    /**
     * Calculates the total received dividends for a single stock based on its transaction history,
     * market dividend announcements, and stock splits.
     * Transactions and Splits lists must be sorted by date.
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
     * The input lists (transactions, splits) for each ticker MUST be sorted by date.
     */
    public Map<String, BigDecimal> calculateDividendsPerMonthAuto(
            Map<String, List<Transactions>> transactionsByTicker,
            Map<String, List<Dividend>> marketDividendsByTicker,
            Map<String, List<Splits>> marketSplitsByTicker,
            List<String> tickersToProcess) {

        Map<String, BigDecimal> monthDividendsMap = new TreeMap<>(); // TreeMap to keep months sorted

        for (String ticker : tickersToProcess) {
            List<Dividend> dividendList = marketDividendsByTicker.get(ticker);
            List<Transactions> stockTransactions = transactionsByTicker.getOrDefault(ticker, Collections.emptyList());
            List<Splits> stockSplits = marketSplitsByTicker.getOrDefault(ticker, Collections.emptyList());

            if (dividendList == null || dividendList.isEmpty()) {
                continue;
            }

            for (Dividend dividend : dividendList) {
                LocalDate exDividendDate = dividend.getDividendDate(); // Ex-dividend date

                BigDecimal sharesHeldOnExDate = getSharesHeldOnDate(exDividendDate, stockTransactions, stockSplits);

                if (sharesHeldOnExDate.compareTo(ZERO) > 0) {
                    BigDecimal dividendReceivedForThisInstance = sharesHeldOnExDate.multiply(dividend.getDividendAmount());
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
