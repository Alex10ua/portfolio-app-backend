package com.dev.alex.Service;

import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.PerformanceData;
import com.dev.alex.Model.NonDbModel.PerformancePoint;
import com.dev.alex.Model.NonDbModel.PriceHistoryEntry;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.PriceHistoryCache;
import com.dev.alex.Model.RealizedPnlCache;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.CustomAssetRepository;
import com.dev.alex.Repository.PriceHistoryCacheRepository;
import com.dev.alex.Repository.RealizedPnlCacheRepository;
import com.dev.alex.Repository.TransactionsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PortfolioPerformanceServiceImpl {

    @Autowired
    private TransactionsRepository transactionsRepository;
    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private MarketDataServiceImpl marketDataService;
    @Autowired
    private PriceHistoryCacheRepository priceHistoryCacheRepository;
    @Autowired
    private CustomAssetRepository customAssetRepository;
    @Autowired
    private RealizedPnlCacheRepository realizedPnlCacheRepository;

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int SCALE = 2;

    /**
     * Everything the value maths needs about one ticker the portfolio has traded — held now or
     * sold out. Built once per request so each ticker costs one market-data read and one price
     * series read however many days the series spans.
     *
     * @param market   STOCK/CRYPTO: provider prices, splits, daily history cache
     * @param currency the currency {@code prices} and {@code currentPrice} are quoted in — the
     *                 provider's for market tickers (may be "GBp"), the book currency for custom
     * @param prices   dated closes (daily cache, or a custom asset's recorded history); may be empty
     */
    private record TickerContext(String ticker, boolean market, String currency, List<Splits> splits,
                                 NavigableMap<LocalDate, BigDecimal> prices, BigDecimal currentPrice) {

        /** Close on or before {@code date}; the current price for a custom asset with no history at all. */
        BigDecimal priceOn(LocalDate date) {
            if (prices.isEmpty()) return market ? null : currentPrice;
            Map.Entry<LocalDate, BigDecimal> entry = prices.floorEntry(date);
            return entry == null ? null : entry.getValue();
        }
    }

    public PerformanceData getPerformance(String portfolioId, String period) {
        List<Transactions> allTx = transactionsRepository.findAllByPortfolioIdOrderByDateAsc(portfolioId);
        List<Holdings> holdings = holdingService.getAllHoldingsByPortfolioId(portfolioId);
        Map<String, TickerContext> contexts = loadTickerContexts(portfolioId, holdings, allTx);

        Map<String, BigDecimal> investedByCurrency = calcTotalInvestedByCurrency(allTx);
        Map<String, BigDecimal> dividendsByCurrency = calcTotalDividendsByCurrency(allTx);
        // The cached figure the dashboard shows, so the two pages cannot disagree. It loads splits
        // for every ticker ever traded; loading them from current holdings only (as this method
        // used to) left a fully-sold split stock unadjusted.
        Map<String, BigDecimal> realizedByCurrency = getRealizedPnLByCurrency(portfolioId);

        Map<String, BigDecimal> valueByCurrency = new TreeMap<>();
        Map<String, BigDecimal> openCostByCurrency = new TreeMap<>();
        for (Holdings h : holdings) {
            TickerContext context = contexts.get(h.getTicker());
            BigDecimal price = context != null ? context.currentPrice() : h.getPriceNow();
            if (price == null || price.compareTo(ZERO) <= 0) continue;
            BigDecimal qty = h.getQuantity() != null ? h.getQuantity() : ZERO;
            BigDecimal avgCost = h.getAveragePurchasePrice() != null ? h.getAveragePurchasePrice() : ZERO;
            // Value is in the quote currency, cost in the book currency — they differ for a coin
            // bought in EUR (quoted in USD) and a London line (quoted in pence).
            String quoteCurrency = context != null ? context.currency() : currencyOrUsd(h.getCurrency());
            valueByCurrency.merge(quoteCurrency, qty.multiply(price), BigDecimal::add);
            openCostByCurrency.merge(currencyOrUsd(h.getCurrency()), qty.multiply(avgCost), BigDecimal::add);
        }

        BigDecimal totalInvested = sum(investedByCurrency);
        BigDecimal totalDividends = sum(dividendsByCurrency);
        BigDecimal realizedPnL = sum(realizedByCurrency);
        BigDecimal currentValue = sum(valueByCurrency);
        BigDecimal openCostBasis = sum(openCostByCurrency);

        BigDecimal unrealizedPnL = currentValue.subtract(openCostBasis);
        BigDecimal unrealizedPnLPct = openCostBasis.compareTo(ZERO) != 0
                ? unrealizedPnL.divide(openCostBasis, 6, RoundingMode.HALF_EVEN).multiply(BigDecimal.valueOf(100))
                : ZERO;

        BigDecimal totalReturn = unrealizedPnL.add(realizedPnL).add(totalDividends);
        BigDecimal totalReturnPct = totalInvested.compareTo(ZERO) != 0
                ? totalReturn.divide(totalInvested, 6, RoundingMode.HALF_EVEN).multiply(BigDecimal.valueOf(100))
                : ZERO;

        List<PerformanceData.CashFlow> cashFlows = cashFlows(allTx);
        BigDecimal xirr = calcXirr(cashFlows, currentValue);
        List<PerformancePoint> timeSeries = buildTimeSeries(contexts, allTx, period);

        PerformanceData data = new PerformanceData();
        data.setTotalInvested(totalInvested.setScale(SCALE, RoundingMode.HALF_EVEN));
        data.setCurrentValue(currentValue.setScale(SCALE, RoundingMode.HALF_EVEN));
        data.setUnrealizedPnL(unrealizedPnL.setScale(SCALE, RoundingMode.HALF_EVEN));
        data.setUnrealizedPnLPct(unrealizedPnLPct.setScale(SCALE, RoundingMode.HALF_EVEN));
        data.setRealizedPnL(realizedPnL.setScale(SCALE, RoundingMode.HALF_EVEN));
        data.setTotalDividends(totalDividends.setScale(SCALE, RoundingMode.HALF_EVEN));
        data.setTotalReturn(totalReturn.setScale(SCALE, RoundingMode.HALF_EVEN));
        data.setTotalReturnPct(totalReturnPct.setScale(SCALE, RoundingMode.HALF_EVEN));
        data.setXirr(xirr);
        data.setTimeSeries(timeSeries);
        data.setTotalInvestedByCurrency(scaled(investedByCurrency));
        data.setCurrentValueByCurrency(scaled(valueByCurrency));
        data.setOpenCostBasisByCurrency(scaled(openCostByCurrency));
        data.setRealizedPnLByCurrency(scaled(realizedByCurrency));
        data.setTotalDividendsByCurrency(scaled(dividendsByCurrency));
        data.setCashFlows(cashFlows);
        return data;
    }

    private Map<String, BigDecimal> calcTotalInvestedByCurrency(List<Transactions> txList) {
        Map<String, BigDecimal> out = new TreeMap<>();
        for (Transactions t : txList) {
            if (t.getTransactionType() != TransactionType.BUY || t.getPrice() == null || t.getQuantity() == null) continue;
            BigDecimal comm = t.getCommission() != null ? t.getCommission() : ZERO;
            out.merge(currencyOrUsd(t.getCurrency()), t.getPrice().multiply(t.getQuantity()).add(comm), BigDecimal::add);
        }
        return out;
    }

    private Map<String, BigDecimal> calcTotalDividendsByCurrency(List<Transactions> txList) {
        Map<String, BigDecimal> out = new TreeMap<>();
        for (Transactions t : txList) {
            if (t.getTransactionType() != TransactionType.DIVIDEND || t.getAmount() == null) continue;
            out.merge(currencyOrUsd(t.getCurrency()), t.getAmount(), BigDecimal::add);
        }
        return out;
    }

    /** FIFO buy lot; qty/price stay in the lot's own date basis, split-adjusted at match time. */
    private static final class Lot {
        BigDecimal qty;
        final BigDecimal price;
        final BigDecimal commPerShare;
        final LocalDate date;

        Lot(BigDecimal qty, BigDecimal price, BigDecimal commPerShare, LocalDate date) {
            this.qty = qty;
            this.price = price;
            this.commPerShare = commPerShare;
            this.date = date;
        }
    }

    /**
     * FIFO realized P&L grouped by the SELL transaction's currency (GBp/GBx normalized
     * to GBP; null falls back to USD). Amounts stay in native currency — callers convert.
     */
    private Map<String, BigDecimal> calcRealizedPnLByCurrency(List<Transactions> txList,
                                                              Map<String, List<Splits>> splitsByTicker) {
        Map<String, Deque<Lot>> buyLots = new HashMap<>();
        Map<String, BigDecimal> realizedByCurrency = new HashMap<>();

        for (Transactions tx : txList) {
            String key = tx.getTicker() != null ? tx.getTicker() : tx.getName();
            if (key == null || tx.getQuantity() == null || tx.getPrice() == null) continue;

            if (tx.getTransactionType() == TransactionType.BUY) {
                buyLots.computeIfAbsent(key, k -> new ArrayDeque<>())
                        .addLast(new Lot(tx.getQuantity(), tx.getPrice(), commissionPerShare(tx), tx.getDate()));

            } else if (tx.getTransactionType() == TransactionType.SELL) {
                Deque<Lot> lots = buyLots.getOrDefault(key, new ArrayDeque<>());
                List<Splits> splits = splitsByTicker.get(key);
                BigDecimal sellCommPerShare = commissionPerShare(tx);
                BigDecimal remaining = tx.getQuantity();

                while (remaining.compareTo(ZERO) > 0 && !lots.isEmpty()) {
                    Lot lot = lots.peekFirst();
                    // convert lot to the sell date's share basis
                    BigDecimal f = (lot.date != null && tx.getDate() != null)
                            ? splitFactor(splits, lot.date, tx.getDate())
                            : BigDecimal.ONE;
                    BigDecimal lotQtyAtSell = lot.qty.multiply(f);
                    BigDecimal lotPriceAtSell = lot.price.divide(f, 8, RoundingMode.HALF_EVEN);
                    BigDecimal lotCommAtSell = lot.commPerShare.divide(f, 8, RoundingMode.HALF_EVEN);

                    BigDecimal matched = remaining.min(lotQtyAtSell);
                    BigDecimal gain = tx.getPrice()
                            .subtract(lotPriceAtSell)
                            .subtract(sellCommPerShare)
                            .subtract(lotCommAtSell)
                            .multiply(matched);
                    String currency = normalizeCurrency(tx.getCurrency() != null ? tx.getCurrency() : "USD");
                    realizedByCurrency.merge(currency, gain, BigDecimal::add);
                    lot.qty = lot.qty.subtract(matched.divide(f, 8, RoundingMode.HALF_EVEN));
                    remaining = remaining.subtract(matched);
                    if (lot.qty.compareTo(ZERO) <= 0) lots.pollFirst();
                }
            }
        }
        return realizedByCurrency;
    }

    /**
     * Realized P&L for the dashboard, per currency. Read-through cached in the
     * realizedPnlCache collection; transaction writes evict via
     * {@link #evictRealizedPnLCache}. Splits are loaded from the transaction
     * ticker set — not current holdings — so fully-sold positions (the whole
     * point of realized P&L) still get split-adjusted correctly.
     */
    public Map<String, BigDecimal> getRealizedPnLByCurrency(String portfolioId) {
        Optional<RealizedPnlCache> cached = realizedPnlCacheRepository.findById(portfolioId);
        if (cached.isPresent() && cached.get().getRealizedByCurrency() != null) {
            return cached.get().getRealizedByCurrency();
        }
        Map<String, BigDecimal> computed = computeRealizedPnLByCurrency(portfolioId);
        try {
            realizedPnlCacheRepository.save(new RealizedPnlCache(portfolioId, computed, LocalDateTime.now()));
        } catch (Exception e) {
            // cache write is best-effort — the computed value is still correct
        }
        return computed;
    }

    /** Drop the stored value; next read recomputes. Call on any transaction write. */
    public void evictRealizedPnLCache(String portfolioId) {
        try {
            realizedPnlCacheRepository.deleteById(portfolioId);
        } catch (Exception e) {
            // eviction is best-effort; a stale doc is replaced on next full compute
        }
    }

    private Map<String, BigDecimal> computeRealizedPnLByCurrency(String portfolioId) {
        List<Transactions> allTx = transactionsRepository.findAllByPortfolioIdOrderByDateAsc(portfolioId);

        Map<String, List<Splits>> splitsByTicker = new HashMap<>();
        Set<String> marketTickers = new HashSet<>();
        for (Transactions tx : allTx) {
            // only market-listed assets have real splits; CUSTOM tickers can collide with
            // exchange symbols in marketData (see CLAUDE.md custom-ticker guard)
            if (tx.getTicker() != null
                    && (tx.getAssetType() == Assets.STOCK || tx.getAssetType() == Assets.CRYPTO)) {
                marketTickers.add(tx.getTicker());
            }
        }
        for (String ticker : marketTickers) {
            MarketData md = marketDataService.getMarketDataByTicker(ticker);
            if (md != null && md.getSplits() != null && !md.getSplits().isEmpty()) {
                splitsByTicker.put(ticker, md.getSplits());
            }
        }

        Map<String, BigDecimal> result = new HashMap<>();
        calcRealizedPnLByCurrency(allTx, splitsByTicker).forEach((currency, amount) -> {
            BigDecimal scaled = amount.setScale(SCALE, RoundingMode.HALF_EVEN);
            if (scaled.compareTo(ZERO) != 0) {
                result.put(currency, scaled);
            }
        });
        return result;
    }

    private BigDecimal commissionPerShare(Transactions tx) {
        if (tx.getCommission() == null || tx.getQuantity() == null
                || tx.getQuantity().compareTo(ZERO) == 0) return ZERO;
        return tx.getCommission().divide(tx.getQuantity(), 8, RoundingMode.HALF_EVEN);
    }

    /** BUY out, SELL and DIVIDEND in — as booked, in each transaction's own currency. */
    private List<PerformanceData.CashFlow> cashFlows(List<Transactions> txList) {
        List<PerformanceData.CashFlow> flows = new ArrayList<>();
        for (Transactions tx : txList) {
            if (tx.getDate() == null) continue;
            String currency = currencyOrUsd(tx.getCurrency());
            BigDecimal comm = tx.getCommission() != null ? tx.getCommission() : ZERO;
            if (tx.getTransactionType() == TransactionType.BUY && tx.getPrice() != null && tx.getQuantity() != null) {
                flows.add(new PerformanceData.CashFlow(tx.getDate(),
                        tx.getPrice().multiply(tx.getQuantity()).add(comm).negate(), currency));
            } else if (tx.getTransactionType() == TransactionType.SELL && tx.getPrice() != null && tx.getQuantity() != null) {
                flows.add(new PerformanceData.CashFlow(tx.getDate(),
                        tx.getPrice().multiply(tx.getQuantity()).subtract(comm), currency));
            } else if (tx.getTransactionType() == TransactionType.DIVIDEND && tx.getAmount() != null) {
                flows.add(new PerformanceData.CashFlow(tx.getDate(), tx.getAmount(), currency));
            }
        }
        return flows;
    }

    /** Money-weighted return over native amounts summed without FX — exact for one currency only. */
    private BigDecimal calcXirr(List<PerformanceData.CashFlow> cashFlows, BigDecimal currentValue) {
        if (cashFlows.isEmpty()) return ZERO;
        List<double[]> flows = new ArrayList<>();
        for (PerformanceData.CashFlow cf : cashFlows) {
            flows.add(new double[]{cf.date().toEpochDay(), cf.amount().doubleValue()});
        }
        flows.add(new double[]{LocalDate.now().toEpochDay(), currentValue.doubleValue()});

        double t0 = flows.get(0)[0];
        double r = 0.1;
        for (int i = 0; i < 200; i++) {
            double npv = 0, dnpv = 0;
            for (double[] cf : flows) {
                double t = (cf[0] - t0) / 365.0;
                double amount = cf[1];
                double denom = Math.pow(1 + r, t);
                npv += amount / denom;
                dnpv -= t * amount / ((1 + r) * denom);
            }
            if (Math.abs(dnpv) < 1e-12) break;
            double rNew = r - npv / dnpv;
            if (Math.abs(rNew - r) < 1e-8) { r = rNew; break; }
            r = Math.max(rNew, -0.9999);
        }

        return BigDecimal.valueOf(r * 100).setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * One context per ticker the portfolio holds or has ever bought or sold. A sold-out position
     * still held value on the days before its sale; building the series from current holdings
     * alone dropped it from every past point.
     */
    private Map<String, TickerContext> loadTickerContexts(String portfolioId, List<Holdings> holdings,
                                                         List<Transactions> allTx) {
        Map<String, Holdings> holdingByTicker = new HashMap<>();
        for (Holdings h : holdings) {
            if (h.getTicker() != null) holdingByTicker.put(h.getTicker(), h);
        }
        Map<String, List<Transactions>> tradesByTicker = new LinkedHashMap<>();
        for (Transactions tx : allTx) {
            if (tx.getTicker() == null) continue;
            if (tx.getTransactionType() != TransactionType.BUY && tx.getTransactionType() != TransactionType.SELL) continue;
            tradesByTicker.computeIfAbsent(tx.getTicker(), k -> new ArrayList<>()).add(tx);
        }
        Set<String> tickers = new LinkedHashSet<>(holdingByTicker.keySet());
        tickers.addAll(tradesByTicker.keySet());

        Map<String, TickerContext> contexts = new LinkedHashMap<>();
        for (String ticker : tickers) {
            Holdings holding = holdingByTicker.get(ticker);
            List<Transactions> trades = tradesByTicker.getOrDefault(ticker, List.of());
            Assets assetType = holding != null && holding.getAssetType() != null
                    ? holding.getAssetType()
                    : trades.stream().map(Transactions::getAssetType).filter(Objects::nonNull)
                            .reduce((first, second) -> second).orElse(null);
            boolean market = assetType == Assets.STOCK || assetType == Assets.CRYPTO;
            String bookCurrency = holding != null && holding.getCurrency() != null
                    ? holding.getCurrency()
                    : trades.stream().map(Transactions::getCurrency).filter(Objects::nonNull).findFirst().orElse(null);

            // Custom assets keep a stub marketData doc whose price the custom-asset endpoints
            // update, so it is the current price for both kinds. Holdings.priceNow is only the
            // fallback: it used to be set once, at creation, and never refreshed.
            MarketData md = marketDataService.getMarketDataByTicker(ticker);
            BigDecimal currentPrice = md != null && md.getPrice() != null ? md.getPrice()
                    : holding != null ? holding.getPriceNow() : null;

            NavigableMap<LocalDate, BigDecimal> prices = new TreeMap<>();
            if (market) {
                PriceHistoryCache cache = priceHistoryCacheRepository.findDailyById(ticker).orElse(null);
                if (cache != null && cache.getHistory() != null) putAll(prices, cache.getHistory());
                List<Splits> splits = md != null && md.getSplits() != null && !md.getSplits().isEmpty()
                        ? md.getSplits() : null;
                String currency = md != null && md.getCurrency() != null ? md.getCurrency() : currencyOrUsd(bookCurrency);
                contexts.put(ticker, new TickerContext(ticker, true, currency, splits, prices, currentPrice));
            } else {
                CustomAsset asset = customAssetRepository.findByPortfolioIdAndTicker(portfolioId, ticker).orElse(null);
                if (asset != null && asset.getPriceHistory() != null) putAll(prices, asset.getPriceHistory());
                if (currentPrice == null && asset != null) currentPrice = asset.getPriceNow();
                contexts.put(ticker, new TickerContext(ticker, false, currencyOrUsd(bookCurrency), null, prices, currentPrice));
            }
        }
        return contexts;
    }

    private static void putAll(NavigableMap<LocalDate, BigDecimal> target, List<PriceHistoryEntry> entries) {
        for (PriceHistoryEntry entry : entries) {
            if (entry != null && entry.getDate() != null && entry.getPrice() != null) {
                target.put(entry.getDate(), entry.getPrice());
            }
        }
    }

    private List<PerformancePoint> buildTimeSeries(Map<String, TickerContext> contexts,
                                                    List<Transactions> allTx, String period) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = resolveStartDate(period, endDate, allTx);
        Map<String, List<Transactions>> txByTicker = groupByTicker(allTx);

        List<PerformancePoint> points = new ArrayList<>();
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            points.add(valueOn(current, contexts, txByTicker));
            current = current.plusDays(1);
        }
        return downsample(points, 200);
    }

    /** Portfolio value on one day, per quote currency — the client converts. */
    private PerformancePoint valueOn(LocalDate date, Map<String, TickerContext> contexts,
                                     Map<String, List<Transactions>> txByTicker) {
        Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
        for (TickerContext context : contexts.values()) {
            BigDecimal qty = quantityAtDate(txByTicker.getOrDefault(context.ticker(), List.of()), date,
                    context.splits());
            if (qty.compareTo(ZERO) <= 0) continue;
            BigDecimal price = context.priceOn(date);
            if (price != null && price.compareTo(ZERO) > 0) {
                byCurrency.merge(context.currency(), qty.multiply(price), BigDecimal::add);
            }
        }
        byCurrency.replaceAll((c, v) -> v.setScale(SCALE, RoundingMode.HALF_EVEN));
        BigDecimal nativeSum = byCurrency.values().stream().reduce(ZERO, BigDecimal::add);
        return new PerformancePoint(date, nativeSum.setScale(SCALE, RoundingMode.HALF_EVEN), byCurrency);
    }

    private static Map<String, List<Transactions>> groupByTicker(List<Transactions> allTx) {
        return allTx.stream()
                .filter(t -> t.getTicker() != null)
                .collect(Collectors.groupingBy(Transactions::getTicker));
    }

    private BigDecimal quantityAtDate(List<Transactions> txForTicker, LocalDate date, List<Splits> splits) {
        BigDecimal qty = ZERO;
        for (Transactions tx : txForTicker) {
            if (tx.getDate() == null || tx.getDate().isAfter(date)) continue;
            if (tx.getQuantity() == null) continue;
            // convert tx-date share count to the as-of date's basis
            BigDecimal eff = tx.getQuantity().multiply(splitFactor(splits, tx.getDate(), date));
            if (tx.getTransactionType() == TransactionType.BUY) qty = qty.add(eff);
            else if (tx.getTransactionType() == TransactionType.SELL) qty = qty.subtract(eff);
        }
        return qty.max(ZERO);
    }

    /**
     * Product of split ratios with splitDate in (from, to] — converts a share count
     * from the `from` date's basis to the `to` date's basis. 1 when no splits apply.
     */
    private BigDecimal splitFactor(List<Splits> splits, LocalDate from, LocalDate to) {
        if (splits == null || splits.isEmpty()) return BigDecimal.ONE;
        BigDecimal f = BigDecimal.ONE;
        for (Splits s : splits) {
            if (s.getSplitDate() == null || s.getRatioSplit() == null) continue;
            if (s.getSplitDate().isAfter(from) && !s.getSplitDate().isAfter(to)) {
                f = f.multiply(s.getRatioSplit());
            }
        }
        return f;
    }

    private LocalDate resolveStartDate(String period, LocalDate end, List<Transactions> allTx) {
        String key = period == null ? "ALL" : period.toUpperCase();
        return switch (key) {
            case "1W" -> end.minusWeeks(1);
            case "1M" -> end.minusMonths(1);
            case "3M" -> end.minusMonths(3);
            case "YTD" -> end.withDayOfYear(1);
            case "1Y" -> end.minusYears(1);
            default -> allTx.stream()
                    .map(Transactions::getDate)
                    .filter(Objects::nonNull)
                    .min(LocalDate::compareTo)
                    .orElse(end.minusYears(1));
        };
    }

    private List<PerformancePoint> downsample(List<PerformancePoint> points, int maxPoints) {
        if (points.size() <= maxPoints) return points;
        List<PerformancePoint> result = new ArrayList<>();
        double step = (double) (points.size() - 1) / (maxPoints - 1);
        for (int i = 0; i < maxPoints; i++) {
            result.add(points.get((int) Math.round(i * step)));
        }
        return result;
    }

    /**
     * Month-end portfolio value from the first transaction to today, per quote currency.
     * Stock/crypto prices are in MarketData currency (e.g. GBp pence), custom assets in their
     * holding currency; no FX is applied — the client converts with the rates it holds.
     */
    public List<PerformancePoint> getMonthlyHistory(String portfolioId) {
        List<Transactions> allTx = transactionsRepository.findAllByPortfolioIdOrderByDateAsc(portfolioId);
        List<Holdings> holdings = holdingService.getAllHoldingsByPortfolioId(portfolioId);
        Map<String, TickerContext> contexts = loadTickerContexts(portfolioId, holdings, allTx);
        Map<String, List<Transactions>> txByTicker = groupByTicker(allTx);

        LocalDate firstTxDate = allTx.stream()
                .map(Transactions::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now());

        YearMonth startMonth = YearMonth.from(firstTxDate);
        YearMonth endMonth = YearMonth.now();

        List<PerformancePoint> result = new ArrayList<>();
        YearMonth current = startMonth;
        while (!current.isAfter(endMonth)) {
            LocalDate date = current.equals(endMonth)
                    ? LocalDate.now()
                    : current.atEndOfMonth();
            result.add(valueOn(date, contexts, txByTicker));
            current = current.plusMonths(1);
        }
        return result;
    }

    private static BigDecimal sum(Map<String, BigDecimal> byCurrency) {
        return byCurrency.values().stream().reduce(ZERO, BigDecimal::add);
    }

    private static Map<String, BigDecimal> scaled(Map<String, BigDecimal> byCurrency) {
        Map<String, BigDecimal> out = new TreeMap<>();
        byCurrency.forEach((c, v) -> out.put(c, v.setScale(SCALE, RoundingMode.HALF_EVEN)));
        return out;
    }

    private static String currencyOrUsd(String currency) {
        return currency == null || currency.isBlank() ? "USD" : currency;
    }

    /** GBp/GBx pence collapse to GBP for display-currency purposes. */
    private String normalizeCurrency(String currency) {
        if (currency == null) return "USD";
        return ("GBp".equals(currency) || "GBx".equals(currency)) ? "GBP" : currency;
    }

}
