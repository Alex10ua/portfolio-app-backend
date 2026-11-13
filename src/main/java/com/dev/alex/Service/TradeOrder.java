package com.dev.alex.Service;

import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Transactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The order same-day trades settle in. A transaction carries a date and no time, so trades on
 * one day come back in whatever order they were stored. A day trade entered sell-first then
 * met no shares: the SELL was clamped to nothing and the BUY left a position that no longer
 * exists. Within a day, a SELL that needs more shares than are held waits for that ticker's
 * remaining same-day BUYs; a confirmed full sale (closesPosition) waits only while nothing is
 * held. Every other trade keeps its recorded order, so a same-day sell-then-rebuy is unchanged.
 *
 * <p>Shared by FIFO accounting, dividend entitlements and the performance timelines so the
 * three agree on every day's closing position.
 */
public final class TradeOrder {
    private TradeOrder() {}

    /**
     * @param byDate   BUY/SELL trades, already sorted by date (stable)
     * @param ticker   the key positions are tracked by
     * @param quantity a trade's share count in the basis the caller balances in
     */
    public static List<Transactions> settle(List<Transactions> byDate, Function<Transactions, String> ticker,
                                            Function<Transactions, BigDecimal> quantity) {
        List<Transactions> out = new ArrayList<>(byDate.size());
        Map<String, BigDecimal> held = new HashMap<>();
        int start = 0;
        while (start < byDate.size()) {
            LocalDate day = byDate.get(start).getDate();
            int end = start;
            while (end < byDate.size() && Objects.equals(byDate.get(end).getDate(), day)) end++;
            List<Transactions> pending = new ArrayList<>(byDate.subList(start, end));
            while (!pending.isEmpty()) {
                // A BUY never waits, so some trade is always ready while one is pending.
                int next = 0;
                while (next < pending.size() - 1 && waits(pending.get(next), pending, held, ticker, quantity)) next++;
                Transactions tx = pending.remove(next);
                apply(tx, held, ticker, quantity);
                out.add(tx);
            }
            start = end;
        }
        return out;
    }

    private static boolean waits(Transactions tx, List<Transactions> pending, Map<String, BigDecimal> held,
                                 Function<Transactions, String> ticker, Function<Transactions, BigDecimal> quantity) {
        if (tx.getTransactionType() != TransactionType.SELL) return false;
        String key = key(tx, ticker);
        boolean buyPending = pending.stream().anyMatch(other -> other != tx
                && other.getTransactionType() == TransactionType.BUY && key.equals(key(other, ticker)));
        if (!buyPending) return false;
        BigDecimal have = held.getOrDefault(key, BigDecimal.ZERO);
        if (Boolean.TRUE.equals(tx.getClosesPosition())) return have.signum() <= 0;
        return quantity.apply(tx).compareTo(have) > 0;
    }

    private static void apply(Transactions tx, Map<String, BigDecimal> held,
                              Function<Transactions, String> ticker, Function<Transactions, BigDecimal> quantity) {
        String key = key(tx, ticker);
        BigDecimal have = held.getOrDefault(key, BigDecimal.ZERO);
        if (tx.getTransactionType() == TransactionType.BUY) {
            held.put(key, have.add(quantity.apply(tx)));
        } else if (tx.getTransactionType() == TransactionType.SELL) {
            held.put(key, Boolean.TRUE.equals(tx.getClosesPosition())
                    ? BigDecimal.ZERO : have.subtract(quantity.apply(tx)).max(BigDecimal.ZERO));
        }
    }

    private static String key(Transactions tx, Function<Transactions, String> ticker) {
        return Objects.toString(ticker.apply(tx), "");
    }
}
