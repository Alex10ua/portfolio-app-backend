package com.dev.alex.Service.Ai;

import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Ai.AiDividendCalendar;
import com.dev.alex.Model.NonDbModel.Ai.AiDividends;
import com.dev.alex.Model.NonDbModel.Ai.AiWatchlistRow;
import com.dev.alex.Model.NonDbModel.DividendInfoCompleteData;
import com.dev.alex.Model.NonDbModel.DividendsCalendarData;
import com.dev.alex.Model.NonDbModel.WatchlistEntry;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Service.DividendCalendarServiceImpl;
import com.dev.alex.Service.DividendsServiceImpl;
import com.dev.alex.Service.WatchlistServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Dividend income and watchlist views, reshaped so every series stays inside one
 * currency and the derived figures a caller would otherwise have to compute
 * (year/quarter rollups, yield percentiles) arrive ready.
 */
@Service
public class AiIncomeAssembler {

    @Autowired
    private DividendsServiceImpl dividendsService;
    @Autowired
    private DividendCalendarServiceImpl dividendCalendarService;
    @Autowired
    private WatchlistServiceImpl watchlistService;
    @Autowired
    private MarketDataRepository marketDataRepository;

    // ---------------------------------------------------------------- dividends

    public AiDividends dividends(String portfolioId) {
        // Same source the Dividends page reads, so the two cannot disagree.
        DividendInfoCompleteData raw = dividendsService.getAllReceivedDividendsInfoFromTransactions(portfolioId);

        Map<String, String> tickerCurrency = raw.getTickerCurrency() == null
                ? Map.of() : raw.getTickerCurrency();

        List<AiDividends.TickerAmount> byTicker = new ArrayList<>();
        if (raw.getTickerAmount() != null) {
            for (Map<String, BigDecimal> entry : raw.getTickerAmount()) {
                entry.forEach((ticker, amount) -> byTicker.add(new AiDividends.TickerAmount(
                        ticker, amount, tickerCurrency.get(ticker))));
            }
        }
        byTicker.sort(Comparator.comparing(AiDividends.TickerAmount::amount,
                Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, Map<String, BigDecimal>> monthly = raw.getAmountByMonthByCurrency() == null
                ? Map.of() : raw.getAmountByMonthByCurrency();

        return new AiDividends(
                byTicker,
                monthly,
                regroup(monthly, key -> key.length() >= 4 ? key.substring(0, 4) : key),
                regroup(monthly, AiIncomeAssembler::quarterKey),
                raw.getProjectionByCurrency() == null ? Map.of() : raw.getProjectionByCurrency());
    }

    /** Folds a currency -&gt; ("yyyy-MM" -&gt; amount) map onto a coarser period key. */
    private Map<String, Map<String, BigDecimal>> regroup(
            Map<String, Map<String, BigDecimal>> monthlyByCurrency,
            java.util.function.Function<String, String> keyMapper) {
        Map<String, Map<String, BigDecimal>> out = new TreeMap<>();
        monthlyByCurrency.forEach((currency, series) -> {
            Map<String, BigDecimal> folded = new TreeMap<>();
            series.forEach((month, amount) -> {
                if (month == null || amount == null) return;
                folded.merge(keyMapper.apply(month), amount, BigDecimal::add);
            });
            out.put(currency, folded);
        });
        return out;
    }

    /** "2026-08" -&gt; "2026-Q3". Returns the input unchanged when it is not a month key. */
    static String quarterKey(String month) {
        if (month == null || month.length() < 7) return month;
        try {
            int m = Integer.parseInt(month.substring(5, 7));
            return month.substring(0, 4) + "-Q" + ((m - 1) / 3 + 1);
        } catch (NumberFormatException e) {
            return month;
        }
    }

    // ---------------------------------------------------------------- calendar

    public AiDividendCalendar dividendCalendar(String portfolioId) {
        Map<String, List<DividendsCalendarData>> raw =
                dividendCalendarService.getDividendCalendarByPortfolioId(portfolioId);

        List<String> tickers = raw == null ? List.of() : raw.values().stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(DividendsCalendarData::getTicker)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // The calendar DTO has no currency field at all, which is why the UI has to
        // guess one; join it back from the market-data doc that priced the dividend.
        Map<String, String> currencyByTicker = new HashMap<>();
        if (!tickers.isEmpty()) {
            for (MarketData md : marketDataRepository.findAllByTickerIn(tickers)) {
                if (md.getTicker() != null) currencyByTicker.put(md.getTicker(), md.getCurrency());
            }
        }

        List<AiDividendCalendar.Month> months = new ArrayList<>();
        Map<String, BigDecimal> annual = new TreeMap<>();
        for (Month month : Month.values()) {
            List<DividendsCalendarData> rows = raw == null ? null : raw.get(month.name());
            List<AiDividendCalendar.Entry> entries = new ArrayList<>();
            Map<String, BigDecimal> totals = new TreeMap<>();
            if (rows != null) {
                for (DividendsCalendarData row : rows) {
                    BigDecimal perShare = row.getDividendAmount();
                    BigDecimal shares = row.getStockQuantity();
                    BigDecimal total = perShare == null || shares == null
                            ? null : perShare.multiply(shares);
                    String currency = currencyByTicker.get(row.getTicker());
                    entries.add(new AiDividendCalendar.Entry(
                            row.getTicker(), perShare, shares, total, currency));
                    if (total != null && currency != null) {
                        totals.merge(currency, total, BigDecimal::add);
                        annual.merge(currency, total, BigDecimal::add);
                    }
                }
            }
            months.add(new AiDividendCalendar.Month(month.name(), month.getValue(), entries, totals));
        }
        return new AiDividendCalendar(months, annual);
    }

    // ---------------------------------------------------------------- watchlist

    public List<AiWatchlistRow> watchlist(String portfolioId, int historyLimit) {
        List<WatchlistEntry> entries = watchlistService.getWatchlist(portfolioId);
        List<AiWatchlistRow> out = new ArrayList<>();
        if (entries == null) return out;
        for (WatchlistEntry entry : entries) {
            // Percentiles are computed over the full series before trimming, so a
            // smaller response never changes the ranking.
            List<AiWatchlistRow.YieldStats> stats = AiYieldMath.statsFor(entry);
            List<WatchlistEntry.YieldPoint> history = entry.getYieldHistory();
            if (history != null && historyLimit > 0 && history.size() > historyLimit) {
                entry.setYieldHistory(new ArrayList<>(
                        history.subList(history.size() - historyLimit, history.size())));
            }
            out.add(new AiWatchlistRow(entry, stats));
        }
        return out;
    }
}
