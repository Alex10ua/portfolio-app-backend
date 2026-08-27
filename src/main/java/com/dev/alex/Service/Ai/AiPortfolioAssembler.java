package com.dev.alex.Service.Ai;

import com.dev.alex.Model.CashHolding;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Ai.AiDiversification;
import com.dev.alex.Model.NonDbModel.Ai.AiPortfolioSummary;
import com.dev.alex.Model.NonDbModel.Ai.AiPosition;
import com.dev.alex.Model.NonDbModel.Ai.AiSnapshot;
import com.dev.alex.Model.NonDbModel.Ai.AiTagGroup;
import com.dev.alex.Model.NonDbModel.HoldingsCompleteData;
import com.dev.alex.Model.Portfolios;
import com.dev.alex.Model.TickerTags;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Model.UserSettings;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Repository.PortfolioRepository;
import com.dev.alex.Service.CashHoldingServiceImpl;
import com.dev.alex.Service.HoldingsCompleteDataServiceImpl;
import com.dev.alex.Service.PortfolioPerformanceServiceImpl;
import com.dev.alex.Service.TagServiceImpl;
import com.dev.alex.Service.TransactionServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns the SPA-facing holding, cash and tag data into the enriched, per-currency
 * shape the AI endpoints answer in. Reads existing services only — no new
 * business logic, no FX.
 */
@Service
public class AiPortfolioAssembler {

    @Autowired
    private HoldingsCompleteDataServiceImpl holdingsService;
    @Autowired
    private MarketDataRepository marketDataRepository;
    @Autowired
    private TagServiceImpl tagService;
    @Autowired
    private CashHoldingServiceImpl cashHoldingService;
    @Autowired
    private TransactionServiceImpl transactionService;
    @Autowired
    private PortfolioPerformanceServiceImpl performanceService;
    @Autowired
    private PortfolioRepository portfolioRepository;
    @Autowired
    private AiEnvelopeService envelopeService;

    // ---------------------------------------------------------------- positions

    public List<AiPosition> positions(String portfolioId, String username) {
        List<HoldingsCompleteData> holdings = holdingsService.getAllHoldingsByPortfolioId(portfolioId);
        if (holdings == null || holdings.isEmpty()) return List.of();

        List<String> tickers = holdings.stream()
                .map(HoldingsCompleteData::getTicker)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // Full documents, not findByTickerForHoldingsPage: that projection strips
        // exactly the fields this view exists to add (currency, sector, updatedAt).
        Map<String, MarketData> marketByTicker = new HashMap<>();
        for (MarketData md : marketDataRepository.findAllByTickerIn(tickers)) {
            if (md.getTicker() != null) marketByTicker.put(md.getTicker(), md);
        }

        Map<String, List<String>> tagsByTicker = new HashMap<>();
        List<TickerTags> allTags = tagService.getAllTagsForUser(username, portfolioId);
        if (allTags != null) {
            for (TickerTags t : allTags) {
                if (t.getTicker() != null && t.getTags() != null) {
                    tagsByTicker.put(t.getTicker(), t.getTags());
                }
            }
        }

        Map<String, Double> targets = new HashMap<>();
        envelopeService.portfolioSettings(username, portfolioId)
                .map(UserSettings.PortfolioSettings::getTargets)
                .ifPresent(list -> list.forEach(t -> {
                    if (t.getTicker() != null) targets.put(t.getTicker(), t.getPercent());
                }));

        // Bucket totals first — percentOfCurrencyBucket needs the denominator.
        Map<String, BigDecimal> valueByCurrency = new HashMap<>();
        for (HoldingsCompleteData h : holdings) {
            valueByCurrency.merge(currencyOf(h), marketValue(h), BigDecimal::add);
        }

        LocalDate today = LocalDate.now();
        List<AiPosition> out = new ArrayList<>(holdings.size());
        for (HoldingsCompleteData h : holdings) {
            MarketData md = h.getTicker() == null ? null : marketByTicker.get(h.getTicker());
            String bookCurrency = currencyOf(h);
            BigDecimal value = marketValue(h);
            BigDecimal bucket = valueByCurrency.getOrDefault(bookCurrency, BigDecimal.ZERO);
            LocalDate updatedAt = md == null ? null : md.getUpdatedAt();

            out.add(new AiPosition(
                    h.getTicker(),
                    h.getName(),
                    h.getAssetType(),
                    h.getShareAmount(),
                    h.getCostPerShare(),
                    h.getCostBasis(),
                    h.getCurrentShareValue(),
                    value,
                    h.getTotalProfit(),
                    h.getTotalProfitPercentage(),
                    h.getDailyChange(),
                    h.getDividend(),
                    h.getDividendYield(),
                    h.getDividendYieldOnCost(),
                    h.getTotalReceivedDividend(),
                    bookCurrency,
                    md == null ? null : md.getCurrency(),
                    percentOf(value, bucket),
                    targets.get(h.getTicker()),
                    h.getSharesOutstanding(),
                    md == null ? null : md.getSector(),
                    md == null ? null : md.getCountry(),
                    md == null ? null : md.getIndustry(),
                    updatedAt,
                    daysStale(updatedAt, today),
                    tagsByTicker.getOrDefault(h.getTicker(), List.of())));
        }
        out.sort(Comparator.comparing(AiPosition::marketValue, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    // ---------------------------------------------------------------- snapshot

    public AiSnapshot snapshot(String portfolioId, String username) {
        List<AiPosition> positions = positions(portfolioId, username);
        Portfolios portfolio = portfolioRepository.findByPortfolioId(portfolioId);

        Map<String, BigDecimal[]> agg = new TreeMap<>();   // currency -> [value, cost, profit]
        Map<String, Integer> counts = new HashMap<>();
        int stale = 0;
        for (AiPosition p : positions) {
            BigDecimal[] a = agg.computeIfAbsent(p.currency(),
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = a[0].add(nz(p.marketValue()));
            a[1] = a[1].add(nz(p.costBasis()));
            a[2] = a[2].add(nz(p.unrealizedProfit()));
            counts.merge(p.currency(), 1, Integer::sum);
            if (p.daysStale() != null && p.daysStale() > AiEnvelopeService.STALE_DAYS) stale++;
        }

        List<AiSnapshot.CurrencyTotal> totals = new ArrayList<>();
        agg.forEach((currency, a) -> totals.add(new AiSnapshot.CurrencyTotal(
                currency, a[0], a[1], a[2], percentOf(a[2], a[1]), counts.getOrDefault(currency, 0))));

        List<AiSnapshot.CashEntry> manualCash = new ArrayList<>();
        List<CashHolding> cash = cashHoldingService.getCashHoldings(portfolioId);
        if (cash != null) {
            cash.forEach(c -> manualCash.add(
                    new AiSnapshot.CashEntry(c.getCurrency(), c.getAmount(), c.getUpdatedAt())));
        }

        return new AiSnapshot(
                portfolioId,
                portfolio == null ? null : portfolio.getPortfolioName(),
                LocalDate.now(),
                positions,
                totals,
                manualCash,
                derivedCashBalance(portfolioId),
                performanceService.getRealizedPnLByCurrency(portfolioId),
                positions.size(),
                stale);
    }

    /**
     * Mirrors TransactionController.getCashBalance — deposits minus withdrawals.
     * Deliberately identical, including the omission of BUY/SELL/DIVIDEND/TAX
     * flows, so the AI answer and the dashboard cannot disagree.
     */
    public Map<String, BigDecimal> derivedCashBalance(String portfolioId) {
        Map<String, BigDecimal> balance = new TreeMap<>();
        List<Transactions> transactions = transactionService.findAllByPortfolioId(portfolioId);
        if (transactions == null) return balance;
        for (Transactions t : transactions) {
            if (t.getTransactionType() == null || t.getCurrency() == null || t.getAmount() == null) continue;
            BigDecimal current = balance.getOrDefault(t.getCurrency(), BigDecimal.ZERO);
            if (t.getTransactionType() == TransactionType.DEPOSIT) {
                balance.put(t.getCurrency(), current.add(t.getAmount()));
            } else if (t.getTransactionType() == TransactionType.WITHDRAWAL) {
                balance.put(t.getCurrency(), current.subtract(t.getAmount()));
            }
        }
        return balance;
    }

    // ---------------------------------------------------------------- summaries

    public List<AiPortfolioSummary> summaries(String username) {
        List<Portfolios> portfolios = portfolioRepository.findAllByUsername(username);
        List<AiPortfolioSummary> out = new ArrayList<>();
        if (portfolios == null) return out;
        for (Portfolios p : portfolios) {
            List<HoldingsCompleteData> holdings = holdingsService.getAllHoldingsByPortfolioId(p.getPortfolioId());
            List<String> currencies = holdings == null ? List.of()
                    : new ArrayList<>(new TreeSet<>(holdings.stream().map(this::currencyOf).toList()));
            out.add(new AiPortfolioSummary(
                    p.getPortfolioId(),
                    p.getPortfolioName(),
                    p.getDescription(),
                    p.getFirstTradeYear() == null ? null : p.getFirstTradeYear().getYear(),
                    holdings == null ? 0 : holdings.size(),
                    currencies,
                    envelopeService.baseCurrency(username, p.getPortfolioId(), currencies)));
        }
        return out;
    }

    // ---------------------------------------------------------------- breakdowns

    /**
     * Diversification rebuilt per currency. The SPA-facing DiversificationCompleteData
     * adds unlike currencies into one number with no currency tag, which an AI
     * cannot recover from; this keeps each bucket convertible.
     */
    public AiDiversification diversification(List<AiPosition> positions) {
        List<String> unclassified = positions.stream()
                .filter(p -> p.sector() == null && p.country() == null)
                .map(AiPosition::ticker)
                .toList();
        return new AiDiversification(
                bucketsBy(positions, AiPosition::country),
                bucketsBy(positions, AiPosition::sector),
                bucketsBy(positions, AiPosition::industry),
                bucketsBy(positions, AiPosition::ticker),
                unclassified);
    }

    private List<AiDiversification.Bucket> bucketsBy(
            List<AiPosition> positions, java.util.function.Function<AiPosition, String> key) {
        Map<String, Map<String, BigDecimal>> amounts = new LinkedHashMap<>();
        Map<String, List<String>> tickers = new LinkedHashMap<>();
        for (AiPosition p : positions) {
            String k = key.apply(p);
            if (k == null || k.isBlank()) continue;
            amounts.computeIfAbsent(k, x -> new TreeMap<>())
                    .merge(p.currency(), nz(p.marketValue()), BigDecimal::add);
            tickers.computeIfAbsent(k, x -> new ArrayList<>()).add(p.ticker());
        }
        List<AiDiversification.Bucket> out = new ArrayList<>();
        amounts.forEach((k, byCurrency) ->
                out.add(new AiDiversification.Bucket(k, byCurrency, tickers.getOrDefault(k, List.of()))));
        out.sort(Comparator.comparing(AiDiversification.Bucket::name));
        return out;
    }

    /** Tag groups with each tag's holdings and their value per currency. */
    public List<AiTagGroup> tagGroups(List<AiPosition> positions) {
        Map<String, List<AiTagGroup.TagTicker>> byTag = new TreeMap<>();
        Map<String, Map<String, BigDecimal>> totals = new HashMap<>();
        for (AiPosition p : positions) {
            for (String tag : p.tags()) {
                byTag.computeIfAbsent(tag, k -> new ArrayList<>())
                        .add(new AiTagGroup.TagTicker(p.ticker(), p.marketValue(), p.currency()));
                totals.computeIfAbsent(tag, k -> new TreeMap<>())
                        .merge(p.currency(), nz(p.marketValue()), BigDecimal::add);
            }
        }
        List<AiTagGroup> out = new ArrayList<>();
        byTag.forEach((tag, tickers) ->
                out.add(new AiTagGroup(tag, tickers, totals.getOrDefault(tag, Map.of()))));
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** Matches the frontend's holdingTotalValue(): trust the backend figure, fall back for legacy rows. */
    private BigDecimal marketValue(HoldingsCompleteData h) {
        if (h.getCurrentTotalValue() != null) return h.getCurrentTotalValue();
        if (h.getCurrentShareValue() == null || h.getShareAmount() == null) return BigDecimal.ZERO;
        return h.getCurrentShareValue().multiply(h.getShareAmount());
    }

    private String currencyOf(HoldingsCompleteData h) {
        return h.getCurrency() == null || h.getCurrency().isBlank() ? "USD" : h.getCurrency();
    }

    static Integer daysStale(LocalDate updatedAt, LocalDate today) {
        if (updatedAt == null) return null;
        return (int) ChronoUnit.DAYS.between(updatedAt, today);
    }

    static BigDecimal percentOf(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) return null;
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 4, RoundingMode.HALF_EVEN);
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
