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

    public PerformanceData getPerformance(String portfolioId, String period) {
        List<Transactions> allTx = transactionsRepository.findAllByPortfolioIdOrderByDateAsc(portfolioId);
        List<Holdings> holdings = holdingService.getAllHoldingsByPortfolioId(portfolioId);
        Map<String, List<Splits>> splitsByTicker = loadSplits(holdings);

        BigDecimal totalInvested = calcTotalInvested(allTx);
        BigDecimal totalDividends = calcTotalDividends(allTx);
        BigDecimal realizedPnL = calcRealizedPnL(allTx, splitsByTicker);

        BigDecimal currentValue = ZERO;
        BigDecimal openCostBasis = ZERO;
        for (Holdings h : holdings) {
            BigDecimal price = getCurrentPrice(h);
            if (price == null || price.compareTo(ZERO) <= 0) continue;
            BigDecimal qty = h.getQuantity() != null ? h.getQuantity() : ZERO;
            currentValue = currentValue.add(qty.multiply(price));
            BigDecimal avgCost = h.getAveragePurchasePrice() != null ? h.getAveragePurchasePrice() : ZERO;
            openCostBasis = openCostBasis.add(qty.multiply(avgCost));
        }

        BigDecimal unrealizedPnL = currentValue.subtract(openCostBasis);
        BigDecimal unrealizedPnLPct = openCostBasis.compareTo(ZERO) != 0
                ? unrealizedPnL.divide(openCostBasis, 6, RoundingMode.HALF_EVEN).multiply(BigDecimal.valueOf(100))
                : ZERO;

        BigDecimal totalReturn = unrealizedPnL.add(realizedPnL).add(totalDividends);
        BigDecimal totalReturnPct = totalInvested.compareTo(ZERO) != 0
                ? totalReturn.divide(totalInvested, 6, RoundingMode.HALF_EVEN).multiply(BigDecimal.valueOf(100))
                : ZERO;

        BigDecimal xirr = calcXirr(allTx, currentValue);
        List<PerformancePoint> timeSeries = buildTimeSeries(portfolioId, holdings, allTx, period, splitsByTicker);

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
        return data;
    }

    private BigDecimal calcTotalInvested(List<Transactions> txList) {
        return txList.stream()
                .filter(t -> t.getTransactionType() == TransactionType.BUY
                        && t.getPrice() != null && t.getQuantity() != null)
                .map(t -> {
                    BigDecimal cost = t.getPrice().multiply(t.getQuantity());
                    BigDecimal comm = t.getCommission() != null ? t.getCommission() : ZERO;
                    return cost.add(comm);
                })
                .reduce(ZERO, BigDecimal::add);
    }

    private BigDecimal calcTotalDividends(List<Transactions> txList) {
        return txList.stream()
                .filter(t -> t.getTransactionType() == TransactionType.DIVIDEND && t.getAmount() != null)
                .map(Transactions::getAmount)
                .reduce(ZERO, BigDecimal::add);
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

    private BigDecimal calcRealizedPnL(List<Transactions> txList, Map<String, List<Splits>> splitsByTicker) {
        // sum of per-currency gains — regrouped but unrounded, so identical to the old single accumulator
        return calcRealizedPnLByCurrency(txList, splitsByTicker).values().stream()
                .reduce(ZERO, BigDecimal::add);
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

    private BigDecimal getCurrentPrice(Holdings h) {
        if (h.getAssetType() == Assets.STOCK || h.getAssetType() == Assets.CRYPTO) {
            MarketData md = marketDataService.getMarketDataByTicker(h.getTicker());
            return md != null ? md.getPrice() : null;
        }
        return h.getPriceNow();
    }

    private BigDecimal calcXirr(List<Transactions> txList, BigDecimal currentValue) {
        List<double[]> flows = new ArrayList<>();

        for (Transactions tx : txList) {
            if (tx.getDate() == null) continue;
            double dayEpoch = tx.getDate().toEpochDay();

            if (tx.getTransactionType() == TransactionType.BUY
                    && tx.getPrice() != null && tx.getQuantity() != null) {
                BigDecimal comm = tx.getCommission() != null ? tx.getCommission() : ZERO;
                double amount = -(tx.getPrice().multiply(tx.getQuantity()).add(comm)).doubleValue();
                flows.add(new double[]{dayEpoch, amount});

            } else if (tx.getTransactionType() == TransactionType.SELL
                    && tx.getPrice() != null && tx.getQuantity() != null) {
                BigDecimal comm = tx.getCommission() != null ? tx.getCommission() : ZERO;
                double amount = tx.getPrice().multiply(tx.getQuantity()).subtract(comm).doubleValue();
                flows.add(new double[]{dayEpoch, amount});

            } else if (tx.getTransactionType() == TransactionType.DIVIDEND && tx.getAmount() != null) {
                flows.add(new double[]{dayEpoch, tx.getAmount().doubleValue()});
            }
        }

        if (flows.isEmpty()) return ZERO;
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

    private List<PerformancePoint> buildTimeSeries(String portfolioId, List<Holdings> holdings,
                                                    List<Transactions> allTx, String period,
                                                    Map<String, List<Splits>> splitsByTicker) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = resolveStartDate(period, endDate, allTx);

        // Load price history for stock-type holdings
        Map<String, Map<LocalDate, BigDecimal>> stockPrices = new HashMap<>();
        for (Holdings h : holdings) {
            if (h.getAssetType() != Assets.STOCK && h.getAssetType() != Assets.CRYPTO) continue;
            String ticker = h.getTicker();
            PriceHistoryCache cache = priceHistoryCacheRepository.findDailyById(ticker).orElse(null);
            if (cache != null && cache.getHistory() != null) {
                Map<LocalDate, BigDecimal> priceMap = cache.getHistory().stream()
                        .collect(Collectors.toMap(PriceHistoryEntry::getDate, PriceHistoryEntry::getPrice,
                                (a, b) -> b));
                stockPrices.put(ticker, priceMap);
            }
        }

        // Flat price for non-stock holdings (use current priceNow)
        Map<String, BigDecimal> flatPrices = new HashMap<>();
        for (Holdings h : holdings) {
            if (h.getAssetType() == Assets.STOCK || h.getAssetType() == Assets.CRYPTO) continue;
            if (h.getPriceNow() != null) flatPrices.put(h.getTicker(), h.getPriceNow());
        }

        // Group transactions by ticker for quantity replay
        Map<String, List<Transactions>> txByTicker = allTx.stream()
                .filter(t -> t.getTicker() != null)
                .collect(Collectors.groupingBy(Transactions::getTicker));

        // Values stay in each ticker's quote currency — the client converts.
        Map<String, String> tickerCurrency = nativeCurrencyByTicker(holdings, null);

        List<PerformancePoint> points = new ArrayList<>();
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
            for (Holdings h : holdings) {
                String ticker = h.getTicker();
                BigDecimal qty = quantityAtDate(txByTicker.getOrDefault(ticker, List.of()), current,
                        splitsByTicker.get(ticker));
                if (qty.compareTo(ZERO) <= 0) continue;

                BigDecimal price;
                if (h.getAssetType() == Assets.STOCK || h.getAssetType() == Assets.CRYPTO) {
                    price = priceOnOrBefore(stockPrices.get(ticker), current);
                } else {
                    price = flatPrices.get(ticker);
                }
                if (price != null && price.compareTo(ZERO) > 0) {
                    byCurrency.merge(tickerCurrency.getOrDefault(ticker, "USD"), qty.multiply(price), BigDecimal::add);
                }
            }
            byCurrency.replaceAll((c, v) -> v.setScale(SCALE, RoundingMode.HALF_EVEN));
            BigDecimal nativeSum = byCurrency.values().stream().reduce(ZERO, BigDecimal::add);
            points.add(new PerformancePoint(current, nativeSum.setScale(SCALE, RoundingMode.HALF_EVEN), byCurrency));
            current = current.plusDays(1);
        }

        return downsample(points, 200);
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

    private Map<String, List<Splits>> loadSplits(List<Holdings> holdings) {
        Map<String, List<Splits>> map = new HashMap<>();
        for (Holdings h : holdings) {
            if (h.getAssetType() != Assets.STOCK && h.getAssetType() != Assets.CRYPTO) continue;
            MarketData md = marketDataService.getMarketDataByTicker(h.getTicker());
            if (md != null && md.getSplits() != null && !md.getSplits().isEmpty()) {
                map.put(h.getTicker(), md.getSplits());
            }
        }
        return map;
    }

    private BigDecimal priceOnOrBefore(Map<LocalDate, BigDecimal> priceMap, LocalDate date) {
        if (priceMap == null || priceMap.isEmpty()) return null;
        LocalDate best = null;
        for (LocalDate d : priceMap.keySet()) {
            if (!d.isAfter(date) && (best == null || d.isAfter(best))) best = d;
        }
        return best != null ? priceMap.get(best) : null;
    }

    private LocalDate resolveStartDate(String period, LocalDate end, List<Transactions> allTx) {
        return switch (period.toUpperCase()) {
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

    public List<PerformancePoint> getMonthlyHistory(String portfolioId) {
        List<Transactions> allTx = transactionsRepository.findAllByPortfolioIdOrderByDateAsc(portfolioId);
        List<Holdings> holdings = holdingService.getAllHoldingsByPortfolioId(portfolioId);

        // Stock/crypto price history from cache
        Map<String, Map<LocalDate, BigDecimal>> stockPrices = new HashMap<>();
        for (Holdings h : holdings) {
            if (h.getAssetType() != Assets.STOCK && h.getAssetType() != Assets.CRYPTO) continue;
            PriceHistoryCache cache = priceHistoryCacheRepository.findDailyById(h.getTicker()).orElse(null);
            if (cache != null && cache.getHistory() != null) {
                Map<LocalDate, BigDecimal> priceMap = cache.getHistory().stream()
                        .collect(Collectors.toMap(PriceHistoryEntry::getDate, PriceHistoryEntry::getPrice, (a, b) -> b));
                stockPrices.put(h.getTicker(), priceMap);
            }
        }

        // Custom asset price history from embedded priceHistory list
        Map<String, Map<LocalDate, BigDecimal>> customPrices = new HashMap<>();
        for (Holdings h : holdings) {
            if (h.getAssetType() == Assets.STOCK || h.getAssetType() == Assets.CRYPTO) continue;
            CustomAsset ca = customAssetRepository.findByPortfolioIdAndTicker(portfolioId, h.getTicker()).orElse(null);
            if (ca != null && ca.getPriceHistory() != null && !ca.getPriceHistory().isEmpty()) {
                Map<LocalDate, BigDecimal> priceMap = ca.getPriceHistory().stream()
                        .collect(Collectors.toMap(PriceHistoryEntry::getDate, PriceHistoryEntry::getPrice, (a, b) -> b));
                customPrices.put(h.getTicker(), priceMap);
            } else if (h.getPriceNow() != null) {
                // Fall back to flat current price if no history recorded yet
                customPrices.put(h.getTicker(), Map.of(LocalDate.now(), h.getPriceNow()));
            }
        }

        Map<String, List<Transactions>> txByTicker = allTx.stream()
                .filter(t -> t.getTicker() != null)
                .collect(Collectors.groupingBy(Transactions::getTicker));

        LocalDate firstTxDate = allTx.stream()
                .map(Transactions::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now());

        // Per-ticker native currency — prices are quoted in it and stay in it.
        // Stock/crypto prices are in MarketData currency (e.g. GBp pence); custom in holding currency.
        // No FX is applied here: the client converts with the rates it holds.
        Map<String, List<Splits>> splitsByTicker = new HashMap<>();
        Map<String, String> tickerCurrency = nativeCurrencyByTicker(holdings, splitsByTicker);

        YearMonth startMonth = YearMonth.from(firstTxDate);
        YearMonth endMonth = YearMonth.now();

        List<PerformancePoint> result = new ArrayList<>();
        YearMonth current = startMonth;
        while (!current.isAfter(endMonth)) {
            LocalDate date = current.equals(endMonth)
                    ? LocalDate.now()
                    : current.atEndOfMonth();

            Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
            for (Holdings h : holdings) {
                String ticker = h.getTicker();
                BigDecimal qty = quantityAtDate(txByTicker.getOrDefault(ticker, List.of()), date,
                        splitsByTicker.get(ticker));
                if (qty.compareTo(ZERO) <= 0) continue;

                Map<LocalDate, BigDecimal> priceMap = (h.getAssetType() == Assets.STOCK || h.getAssetType() == Assets.CRYPTO)
                        ? stockPrices.get(ticker)
                        : customPrices.get(ticker);

                BigDecimal price = priceOnOrBefore(priceMap, date);
                if (price != null && price.compareTo(ZERO) > 0) {
                    String ccy = tickerCurrency.getOrDefault(ticker, "USD");
                    byCurrency.merge(ccy, qty.multiply(price), BigDecimal::add);
                }
            }
            byCurrency.replaceAll((c, v) -> v.setScale(SCALE, RoundingMode.HALF_EVEN));
            BigDecimal nativeSum = byCurrency.values().stream().reduce(ZERO, BigDecimal::add);
            result.add(new PerformancePoint(date, nativeSum.setScale(SCALE, RoundingMode.HALF_EVEN), byCurrency));
            current = current.plusMonths(1);
        }
        return result;
    }

    /**
     * Ticker → the currency its price is quoted in (MarketData currency for
     * stock/crypto, holding currency otherwise). Fills {@code splitsSink} with
     * each ticker's splits along the way so callers need only one MarketData read.
     */
    private Map<String, String> nativeCurrencyByTicker(List<Holdings> holdings,
                                                       Map<String, List<Splits>> splitsSink) {
        Map<String, String> currencies = new HashMap<>();
        for (Holdings h : holdings) {
            String ccy;
            if (h.getAssetType() == Assets.STOCK || h.getAssetType() == Assets.CRYPTO) {
                MarketData md = marketDataService.getMarketDataByTicker(h.getTicker());
                ccy = (md != null && md.getCurrency() != null) ? md.getCurrency() : h.getCurrency();
                if (splitsSink != null && md != null && md.getSplits() != null && !md.getSplits().isEmpty()) {
                    splitsSink.put(h.getTicker(), md.getSplits());
                }
            } else {
                ccy = h.getCurrency();
            }
            currencies.put(h.getTicker(), ccy != null ? ccy : "USD");
        }
        return currencies;
    }

    /** GBp/GBx pence collapse to GBP for display-currency purposes. */
    private String normalizeCurrency(String currency) {
        if (currency == null) return "USD";
        return ("GBp".equals(currency) || "GBx".equals(currency)) ? "GBP" : currency;
    }

}
