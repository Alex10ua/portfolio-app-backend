package com.dev.alex.Service;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.Transactions;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.*;

/** FIFO accounting in native currencies. Prices never need FX or split rounding here. */
@Component
public class PortfolioAccounting {
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    public record Position(BigDecimal quantity, BigDecimal costBasis, BigDecimal commissions, String currency,
                           Map<String, BigDecimal> costBasisByCurrency,
                           Map<String, BigDecimal> commissionsByCurrency) {}

    public record Result(Map<String, Position> positions, Map<String, BigDecimal> realizedPnLByCurrency,
                         Map<String, BigDecimal> commissionsByCurrency,
                         Map<String, BigDecimal> realizedCommissionsByCurrency) {}

    /**
     * Shares are expressed in the basis of asOf; each lot keeps its exact remaining money totals.
     * Fully consumed lots are removed explicitly, including quantities smaller than eight decimals.
     * A partial import's oversell is clamped to the available lots, as on the existing holding path.
     */
    public Result calculate(List<Transactions> transactions, Map<String, List<Splits>> splitsByTicker,
                            LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        Map<String, SplitTimeline> timelines = new HashMap<>();
        if (splitsByTicker != null) splitsByTicker.forEach((ticker, splits) -> {
            if (ticker != null) timelines.put(ticker.toUpperCase(Locale.ROOT), new SplitTimeline(splits, asOf));
        });
        List<Transactions> trades = transactions == null ? List.of() : transactions.stream()
                .filter(tx -> validTrade(tx, asOf))
                // Stable sorting retains a statement's order for trades on the same calendar day.
                .sorted(Comparator.comparing(Transactions::getDate)).toList();
        Map<String, Deque<Lot>> lotsByTicker = new TreeMap<>();
        Map<String, BigDecimal> realized = new TreeMap<>();
        Map<String, BigDecimal> commissions = new TreeMap<>();
        Map<String, BigDecimal> realizedCommissions = new TreeMap<>();

        for (Transactions tx : trades) {
            String ticker = tickerOf(tx);
            String currency = currencyOf(tx.getCurrency());
            BigDecimal fee = tx.getCommission() == null ? BigDecimal.ZERO : tx.getCommission();
            add(commissions, currency, fee);
            SplitTimeline timeline = timelines.get(ticker);
            boolean market = tx.getAssetType() == null || tx.getAssetType() == Assets.STOCK
                    || tx.getAssetType() == Assets.CRYPTO;
            BigDecimal quantity = tx.getQuantity().multiply(market && timeline != null
                    ? timeline.factorAfter(tx.getDate()) : BigDecimal.ONE);
            BigDecimal gross = tx.getQuantity().multiply(tx.getPrice());
            Deque<Lot> lots = lotsByTicker.computeIfAbsent(ticker, ignored -> new ArrayDeque<>());
            if (tx.getTransactionType() == TransactionType.BUY) {
                lots.addLast(new Lot(quantity, gross.add(fee), fee, currency));
                continue;
            }

            BigDecimal remaining = quantity;
            while (remaining.signum() > 0 && !lots.isEmpty()) {
                Lot lot = lots.peekFirst();
                BigDecimal matched = remaining.min(lot.quantity);
                boolean entireLot = matched.compareTo(lot.quantity) == 0;
                BigDecimal cost = entireLot ? lot.cost : fraction(lot.cost, matched, lot.quantity);
                BigDecimal buyFee = entireLot ? lot.fee : fraction(lot.fee, matched, lot.quantity);
                add(realized, lot.currency, cost.negate());
                add(realizedCommissions, lot.currency, buyFee);
                remaining = remaining.subtract(matched);
                if (entireLot) {
                    lots.removeFirst();
                } else {
                    lot.quantity = lot.quantity.subtract(matched);
                    lot.cost = lot.cost.subtract(cost);
                    lot.fee = lot.fee.subtract(buyFee);
                }
            }
            BigDecimal matched = quantity.subtract(remaining);
            if (matched.signum() > 0) {
                BigDecimal sellFee = fraction(fee, matched, quantity);
                add(realized, currency, fraction(gross, matched, quantity).subtract(sellFee));
                add(realizedCommissions, currency, sellFee);
            }
        }

        Map<String, Position> positions = new TreeMap<>();
        lotsByTicker.forEach((ticker, lots) -> {
            if (lots.isEmpty()) return;
            BigDecimal quantity = BigDecimal.ZERO;
            Map<String, BigDecimal> costs = new TreeMap<>();
            Map<String, BigDecimal> fees = new TreeMap<>();
            for (Lot lot : lots) {
                quantity = quantity.add(lot.quantity);
                costs.merge(lot.currency, lot.cost, BigDecimal::add);
                fees.merge(lot.currency, lot.fee, BigDecimal::add);
            }
            String currency = costs.size() == 1 ? costs.keySet().iterator().next() : null;
            positions.put(ticker, new Position(quantity, currency == null ? null : costs.get(currency),
                    currency == null ? null : fees.get(currency), currency, immutable(costs), immutable(fees)));
        });
        return new Result(Collections.unmodifiableMap(positions), immutable(realized), immutable(commissions),
                immutable(realizedCommissions));
    }

    private boolean validTrade(Transactions tx, LocalDate asOf) {
        return tx != null && (tx.getTransactionType() == TransactionType.BUY
                || tx.getTransactionType() == TransactionType.SELL) && tickerOf(tx) != null
                && tx.getDate() != null && !tx.getDate().isAfter(asOf)
                && tx.getQuantity() != null && tx.getQuantity().signum() > 0
                && tx.getPrice() != null && tx.getPrice().signum() >= 0;
    }

    private String tickerOf(Transactions tx) {
        String ticker = tx.getTicker() == null || tx.getTicker().isBlank() ? tx.getName() : tx.getTicker();
        return ticker == null || ticker.isBlank() ? null : ticker.toUpperCase(Locale.ROOT);
    }

    private String currencyOf(String currency) {
        return currency == null || currency.isBlank() ? "USD" : currency;
    }

    private BigDecimal fraction(BigDecimal amount, BigDecimal numerator, BigDecimal denominator) {
        return numerator.compareTo(denominator) == 0 ? amount
                : amount.multiply(numerator).divide(denominator, PRECISION);
    }

    private void add(Map<String, BigDecimal> totals, String currency, BigDecimal amount) {
        if (amount.signum() != 0) totals.merge(currency, amount, BigDecimal::add);
    }

    private static Map<String, BigDecimal> immutable(Map<String, BigDecimal> values) {
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }

    private static final class Lot {
        BigDecimal quantity;
        BigDecimal cost;
        BigDecimal fee;
        final String currency;

        Lot(BigDecimal quantity, BigDecimal cost, BigDecimal fee, String currency) {
            this.quantity = quantity;
            this.cost = cost;
            this.fee = fee;
            this.currency = currency;
        }
    }

    /** Suffix products make each trade's split lookup logarithmic instead of rescanning all splits. */
    private static final class SplitTimeline {
        final LocalDate[] dates;
        final BigDecimal[] factors;

        SplitTimeline(List<Splits> splits, LocalDate asOf) {
            NavigableMap<LocalDate, BigDecimal> ratios = new TreeMap<>();
            if (splits != null) for (Splits split : splits) {
                if (split != null && split.getSplitDate() != null && !split.getSplitDate().isAfter(asOf)
                        && split.getRatioSplit() != null && split.getRatioSplit().signum() > 0) {
                    ratios.put(split.getSplitDate(), split.getRatioSplit());
                }
            }
            dates = ratios.keySet().toArray(LocalDate[]::new);
            factors = new BigDecimal[dates.length + 1];
            factors[dates.length] = BigDecimal.ONE;
            for (int i = dates.length - 1; i >= 0; i--) {
                factors[i] = ratios.get(dates[i]).multiply(factors[i + 1]);
            }
        }

        BigDecimal factorAfter(LocalDate date) {
            int index = Arrays.binarySearch(dates, date);
            return factors[index >= 0 ? index + 1 : -index - 1];
        }
    }
}
