package com.dev.alex.Service.Ai;

import com.dev.alex.Model.CompanyFundamentals;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Ai.AiTickerData;
import com.dev.alex.Model.NonDbModel.Ai.AiTickerHistorical;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.NonDbModel.TickerHistoricalData;
import com.dev.alex.Model.SharesOutstandingHistory.SharesHistoryEntry;
import com.dev.alex.Repository.CompanyFundamentalsRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Service.MarketDataServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Ticker-level research views. Reads the full market-data document and derives
 * the dividend-growth figures the Historical page computes client-side, so a
 * model gets conclusions rather than a raw series to fold.
 */
@Service
public class AiTickerAssembler {

    @Autowired
    private MarketDataRepository marketDataRepository;
    @Autowired
    private MarketDataServiceImpl marketDataService;
    @Autowired
    private CompanyFundamentalsRepository fundamentalsRepository;

    /** null when the ticker has never been fetched by any provider. */
    public AiTickerData ticker(String ticker) {
        MarketData md = marketDataRepository.findByTicker(ticker);
        if (md == null) return null;
        BigDecimal price = md.getPrice();
        BigDecimal previous = md.getPriceYesterday();
        BigDecimal dayChange = null;
        if (price != null && previous != null && previous.signum() != 0) {
            dayChange = price.subtract(previous)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previous, 4, RoundingMode.HALF_EVEN);
        }
        // statistics carries no currency of its own — it is the market-data doc's.
        if (md.getStatistics() != null && md.getStatistics().getCurrency() == null) {
            md.getStatistics().setCurrency(md.getCurrency());
        }
        return new AiTickerData(
                md.getTicker() == null ? ticker : md.getTicker(),
                md.getName(),
                md.getCurrency(),
                price,
                previous,
                dayChange,
                md.getLastDividendPayment(),
                md.getYearlyDividend(),
                md.getSector(),
                md.getCountry(),
                md.getIndustry(),
                md.getSharesOutstanding(),
                md.getUpdatedAt(),
                AiPortfolioAssembler.daysStale(md.getUpdatedAt(), LocalDate.now()),
                md.getStatistics(),
                md.getDividends() == null ? 0 : md.getDividends().size(),
                md.getSplits() == null ? 0 : md.getSplits().size());
    }

    /**
     * @param years keep only events from the last N years; null or &lt;= 0 keeps everything
     */
    public AiTickerHistorical historical(String ticker, Integer years) {
        TickerHistoricalData raw = marketDataService.getHistoricalData(ticker);
        if (raw == null) return null;

        LocalDate cutoff = years != null && years > 0 ? LocalDate.now().minusYears(years) : null;
        List<Dividend> dividends = filter(raw.getDividends(), Dividend::getDividendDate, cutoff);
        List<Splits> splits = filter(raw.getSplits(), Splits::getSplitDate, cutoff);
        List<SharesHistoryEntry> shares = filter(raw.getSharesHistory(), SharesHistoryEntry::getDate, cutoff);

        List<AiTickerHistorical.DividendYear> byYear = dividendsByYear(dividends);
        return new AiTickerHistorical(
                raw.getTicker(),
                raw.getName(),
                raw.getCurrency(),
                dividends,
                splits,
                shares,
                byYear,
                growthStreak(byYear),
                lastRaise(dividends),
                cumulativeSplitFactor(splits));
    }

    public CompanyFundamentals fundamentals(String ticker, int limitPerConcept) {
        CompanyFundamentals doc = fundamentalsRepository.findById(ticker).orElse(null);
        if (doc == null || doc.getConcepts() == null) return doc;
        Map<String, List<CompanyFundamentals.FundamentalEntry>> trimmed = new TreeMap<>();
        doc.getConcepts().forEach((concept, series) -> {
            if (series == null) return;
            List<CompanyFundamentals.FundamentalEntry> sorted = series.stream()
                    .filter(e -> e.getDate() != null)
                    .sorted(Comparator.comparing(CompanyFundamentals.FundamentalEntry::getDate).reversed())
                    .limit(limitPerConcept > 0 ? limitPerConcept : Long.MAX_VALUE)
                    .toList();
            trimmed.put(concept, sorted);
        });
        doc.setConcepts(trimmed);
        return doc;
    }

    // ---------------------------------------------------------------- derived

    /**
     * Per-year dividend totals with year-over-year growth.
     * <p>
     * A year with fewer payments than the ticker's usual cadence is flagged
     * {@code partial} and excluded from the growth comparison — without that, a
     * half-collected current year always reads as a dividend cut.
     */
    static List<AiTickerHistorical.DividendYear> dividendsByYear(List<Dividend> dividends) {
        if (dividends == null || dividends.isEmpty()) return List.of();

        Map<Integer, BigDecimal> totals = new TreeMap<>();
        Map<Integer, Integer> counts = new TreeMap<>();
        for (Dividend d : dividends) {
            if (d.getDividendDate() == null || d.getDividendAmount() == null) continue;
            int year = d.getDividendDate().getYear();
            totals.merge(year, d.getDividendAmount(), BigDecimal::add);
            counts.merge(year, 1, Integer::sum);
        }
        if (totals.isEmpty()) return List.of();

        int cadence = counts.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        int currentYear = LocalDate.now().getYear();

        List<AiTickerHistorical.DividendYear> out = new ArrayList<>();
        BigDecimal previousFull = null;
        for (Map.Entry<Integer, BigDecimal> e : totals.entrySet()) {
            int year = e.getKey();
            int payments = counts.getOrDefault(year, 0);
            boolean partial = year == currentYear || payments < cadence;
            BigDecimal yoy = null;
            if (!partial && previousFull != null && previousFull.signum() > 0) {
                yoy = e.getValue().subtract(previousFull)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(previousFull, 2, RoundingMode.HALF_EVEN);
            }
            out.add(new AiTickerHistorical.DividendYear(year, e.getValue(), payments, partial, yoy));
            if (!partial) previousFull = e.getValue();
        }
        return out;
    }

    /** Consecutive complete years of growth, counted back from the most recent complete year. */
    static int growthStreak(List<AiTickerHistorical.DividendYear> byYear) {
        List<AiTickerHistorical.DividendYear> full = byYear.stream()
                .filter(y -> !y.partial())
                .toList();
        int streak = 0;
        for (int i = full.size() - 1; i > 0; i--) {
            if (full.get(i).total().compareTo(full.get(i - 1).total()) > 0) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    /** Most recent payment that exceeded the one before it. */
    static AiTickerHistorical.LastRaise lastRaise(List<Dividend> dividends) {
        if (dividends == null || dividends.size() < 2) return null;
        for (int i = dividends.size() - 1; i > 0; i--) {
            BigDecimal to = dividends.get(i).getDividendAmount();
            BigDecimal from = dividends.get(i - 1).getDividendAmount();
            if (to == null || from == null || from.signum() <= 0) continue;
            if (to.compareTo(from) > 0) {
                BigDecimal pct = to.subtract(from)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(from, 2, RoundingMode.HALF_EVEN);
                return new AiTickerHistorical.LastRaise(
                        dividends.get(i).getDividendDate(), from, to, pct);
            }
        }
        return null;
    }

    static BigDecimal cumulativeSplitFactor(List<Splits> splits) {
        BigDecimal factor = BigDecimal.ONE;
        if (splits == null) return factor;
        for (Splits s : splits) {
            if (s.getRatioSplit() != null && s.getRatioSplit().signum() > 0) {
                factor = factor.multiply(s.getRatioSplit());
            }
        }
        return factor.stripTrailingZeros();
    }

    private static <T> List<T> filter(List<T> items, java.util.function.Function<T, LocalDate> date, LocalDate cutoff) {
        if (items == null) return List.of();
        if (cutoff == null) return items;
        return items.stream()
                .filter(Objects::nonNull)
                .filter(i -> {
                    LocalDate d = date.apply(i);
                    return d == null || !d.isBefore(cutoff);
                })
                .toList();
    }
}
