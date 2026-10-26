package com.dev.alex.Service;

import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.PerformanceData;
import com.dev.alex.Model.NonDbModel.PerformancePoint;
import com.dev.alex.Model.NonDbModel.PnlSummary;
import com.dev.alex.Model.NonDbModel.PriceHistoryEntry;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.PriceHistoryCache;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.CustomAssetRepository;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Repository.PriceHistoryCacheRepository;
import com.dev.alex.Repository.RealizedPnlCacheRepository;
import com.dev.alex.Repository.TransactionsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PortfolioPerformanceServiceImpl {
    @Autowired
    private TransactionsRepository transactionsRepository;
    @Autowired
    private HoldingsRepository holdingsRepository;
    @Autowired
    private MarketDataRepository marketDataRepository;
    @Autowired
    private PriceHistoryCacheRepository priceHistoryCacheRepository;
    @Autowired
    private CustomAssetRepository customAssetRepository;
    @Autowired
    private RealizedPnlCacheRepository realizedPnlCacheRepository;
    @Autowired
    private PortfolioAccounting accounting;

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int SCALE = 2;

    private record TickerContext(String ticker, boolean market, String currency, List<Splits> splits,
                                 NavigableMap<LocalDate, BigDecimal> prices, Set<LocalDate> adjustedPriceDates,
                                 BigDecimal currentPrice) {
        BigDecimal priceOn(LocalDate date, LocalDate today) {
            if (date.equals(today) && currentPrice != null) return currentPrice;
            if (prices.isEmpty()) return market ? null : currentPrice;
            Map.Entry<LocalDate, BigDecimal> entry = prices.floorEntry(date);
            return entry == null ? null : entry.getValue();
        }

        boolean usesAdjustedCloseOn(LocalDate date, LocalDate today) {
            if (!market || date.equals(today) && currentPrice != null) return false;
            Map.Entry<LocalDate, BigDecimal> entry = prices.floorEntry(date);
            return entry != null && adjustedPriceDates.contains(entry.getKey());
        }
    }

    public PerformanceData getPerformance(String portfolioId, String period) {
        LocalDate today = LocalDate.now();
        List<Transactions> allTx = transactionsAsOf(portfolioId, today);
        List<Holdings> holdings = holdingsRepository.findAllByPortfolioId(portfolioId);
        Map<String, TickerContext> contexts = loadTickerContexts(portfolioId, holdings, allTx);
        Map<String, List<Splits>> splits = splitsByTicker(contexts);
        PortfolioAccounting.Result result = accounting.calculate(allTx, splits, today);

        Map<String, BigDecimal> invested = calcTotalInvestedByCurrency(allTx);
        Map<String, BigDecimal> dividends = calcTotalDividendsByCurrency(allTx);
        Map<String, BigDecimal> values = new TreeMap<>();
        Map<String, BigDecimal> openCost = new TreeMap<>();
        for (Map.Entry<String, PortfolioAccounting.Position> entry : result.positions().entrySet()) {
            PortfolioAccounting.Position position = entry.getValue();
            if (position.quantity().signum() <= 0) continue;
            // Cost is independent of quote availability; zero-priced holdings still have a loss.
            position.costBasisByCurrency().forEach((c, v) -> openCost.merge(c, v, BigDecimal::add));
            TickerContext context = contexts.get(entry.getKey());
            if (context != null && context.currentPrice() != null && context.currentPrice().signum() >= 0) {
                values.merge(context.currency(), position.quantity().multiply(context.currentPrice()), BigDecimal::add);
            }
        }

        Map<String, BigDecimal> realized = result.realizedPnLByCurrency();
        BigDecimal totalInvested = sum(invested);
        BigDecimal totalDividends = sum(dividends);
        BigDecimal realizedPnL = sum(realized);
        BigDecimal currentValue = sum(values);
        BigDecimal openCostBasis = sum(openCost);
        BigDecimal unrealizedPnL = currentValue.subtract(openCostBasis);
        BigDecimal totalReturn = unrealizedPnL.add(realizedPnL).add(totalDividends);
        List<PerformanceData.CashFlow> flows = cashFlows(allTx);

        PerformanceData data = new PerformanceData();
        data.setTotalInvested(money(totalInvested));
        data.setCurrentValue(money(currentValue));
        data.setUnrealizedPnL(money(unrealizedPnL));
        data.setUnrealizedPnLPct(percent(unrealizedPnL, openCostBasis));
        data.setRealizedPnL(money(realizedPnL));
        data.setTotalDividends(money(totalDividends));
        data.setTotalReturn(money(totalReturn));
        data.setTotalReturnPct(percent(totalReturn, totalInvested));
        data.setXirr(calcXirr(flows, currentValue));
        data.setTimeSeries(buildTimeSeries(contexts, allTx, period, today));
        data.setTotalInvestedByCurrency(scaled(invested));
        data.setCurrentValueByCurrency(scaled(values));
        data.setOpenCostBasisByCurrency(scaled(openCost));
        data.setRealizedPnLByCurrency(scaled(realized));
        data.setTotalDividendsByCurrency(scaled(dividends));
        data.setTotalCommissions(money(sum(result.commissionsByCurrency())));
        data.setCommissionsByCurrency(scaled(result.commissionsByCurrency()));
        data.setRealizedCommissionsByCurrency(scaled(result.realizedCommissionsByCurrency()));
        data.setCashFlows(flows);
        return data;
    }

    /**
     * FIFO is linear in the transaction count and uses one projected split read. Do not read the
     * legacy persistent cache: transaction eviction races and provider split changes could leave
     * its result stale indefinitely. The performance request reuses its existing input snapshot.
     */
    public Map<String, BigDecimal> getRealizedPnLByCurrency(String portfolioId) {
        return getPnlSummary(portfolioId).realizedPnLByCurrency();
    }

    /** Dashboard fees and realized gains, without loading prices or historical series. */
    public PnlSummary getPnlSummary(String portfolioId) {
        LocalDate today = LocalDate.now();
        List<Transactions> allTx = transactionsAsOf(portfolioId, today);
        Set<String> tickers = allTx.stream().filter(PortfolioPerformanceServiceImpl::isMarket)
                .map(Transactions::getTicker).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, List<Splits>> splits = new HashMap<>();
        if (!tickers.isEmpty()) {
            for (MarketData md : marketDataRepository.findByTickerInForPerformance(tickers)) {
                if (md.getTicker() != null && md.getSplits() != null) splits.put(md.getTicker(), md.getSplits());
            }
        }
        PortfolioAccounting.Result result = accounting.calculate(allTx, splits, today);
        return new PnlSummary(scaled(result.realizedPnLByCurrency()), scaled(result.commissionsByCurrency()),
                scaled(result.realizedCommissionsByCurrency()));
    }

    /** Kept for existing transaction write paths and to clear obsolete persisted values. */
    public void evictRealizedPnLCache(String portfolioId) {
        try {
            realizedPnlCacheRepository.deleteById(portfolioId);
        } catch (Exception ignored) {
            // Reads no longer depend on this legacy cache.
        }
    }

    private List<Transactions> transactionsAsOf(String portfolioId, LocalDate today) {
        return transactionsRepository.findAllByPortfolioIdOrderByDateAsc(portfolioId).stream()
                .filter(tx -> tx.getDate() != null && !tx.getDate().isAfter(today)).toList();
    }

    private Map<String, TickerContext> loadTickerContexts(String portfolioId, List<Holdings> holdings,
                                                          List<Transactions> allTx) {
        Map<String, Holdings> holdingByTicker = new HashMap<>();
        for (Holdings holding : holdings) {
            if (holding.getTicker() != null) holdingByTicker.put(holding.getTicker(), holding);
        }
        Map<String, List<Transactions>> trades = new LinkedHashMap<>();
        for (Transactions tx : allTx) {
            if (tx.getTicker() != null && isTrade(tx)) {
                trades.computeIfAbsent(tx.getTicker(), k -> new ArrayList<>()).add(tx);
            }
        }
        Set<String> tickers = new LinkedHashSet<>(holdingByTicker.keySet());
        tickers.addAll(trades.keySet());
        if (tickers.isEmpty()) return Map.of();

        Set<String> marketTickers = new LinkedHashSet<>();
        Map<String, String> bookCurrencies = new HashMap<>();
        for (String ticker : tickers) {
            Holdings holding = holdingByTicker.get(ticker);
            List<Transactions> txs = trades.getOrDefault(ticker, List.of());
            Assets type = txs.stream().map(Transactions::getAssetType).filter(Objects::nonNull)
                    .reduce((a, b) -> b).orElse(holding == null ? null : holding.getAssetType());
            if (type == null || type == Assets.STOCK || type == Assets.CRYPTO) marketTickers.add(ticker);
            String currency = txs.stream().map(Transactions::getCurrency).filter(Objects::nonNull)
                    .findFirst().orElse(holding == null ? null : holding.getCurrency());
            bookCurrencies.put(ticker, currencyOrUsd(currency));
        }
        Set<String> customTickers = new LinkedHashSet<>(tickers);
        customTickers.removeAll(marketTickers);
        Map<String, MarketData> marketData = new HashMap<>();
        for (MarketData md : marketDataRepository.findByTickerInForPerformance(tickers)) {
            marketData.put(md.getTicker(), md);
        }
        Map<String, PriceHistoryCache> histories = new HashMap<>();
        if (!marketTickers.isEmpty()) {
            for (PriceHistoryCache history : priceHistoryCacheRepository.findDailyByTickerIn(marketTickers)) {
                histories.put(history.getTicker(), history);
            }
        }
        Map<String, CustomAsset> customAssets = new HashMap<>();
        if (!customTickers.isEmpty()) {
            for (CustomAsset asset : customAssetRepository.findForPerformance(portfolioId, customTickers)) {
                customAssets.put(asset.getTicker(), asset);
            }
        }

        Map<String, TickerContext> contexts = new LinkedHashMap<>();
        for (String ticker : tickers) {
            boolean market = marketTickers.contains(ticker);
            MarketData md = marketData.get(ticker);
            Holdings holding = holdingByTicker.get(ticker);
            CustomAsset asset = customAssets.get(ticker);
            BigDecimal currentPrice = !market && asset != null ? asset.getPriceNow() : null;
            if (currentPrice == null && md != null) currentPrice = md.getPrice();
            if (currentPrice == null && holding != null) currentPrice = holding.getPriceNow();
            String currency = market && md != null && md.getCurrency() != null ? md.getCurrency()
                    : asset != null && asset.getCurrency() != null ? asset.getCurrency() : bookCurrencies.get(ticker);
            NavigableMap<LocalDate, BigDecimal> prices = new TreeMap<>();
            Set<LocalDate> adjustedPriceDates = new HashSet<>();
            PriceHistoryCache history = histories.get(ticker);
            List<PriceHistoryEntry> entries = market ? history == null ? null : history.getHistory()
                    : asset == null ? null : asset.getPriceHistory();
            if (entries != null) {
                for (PriceHistoryEntry entry : entries) {
                    if (entry == null || entry.getDate() == null) continue;
                    // Legacy caches lack rawPrice. Keep their adjusted close until the provider's
                    // next normal refresh adds it; no migration or fabricated dividend reversal.
                    BigDecimal price = market && entry.getRawPrice() != null ? entry.getRawPrice() : entry.getPrice();
                    if (price != null) {
                        prices.put(entry.getDate(), price);
                        if (market && entry.getRawPrice() == null) adjustedPriceDates.add(entry.getDate());
                        else adjustedPriceDates.remove(entry.getDate());
                    }
                }
            }
            List<Splits> splits = market && md != null && md.getSplits() != null ? md.getSplits() : List.of();
            contexts.put(ticker, new TickerContext(ticker, market, currencyOrUsd(currency), splits, prices,
                    adjustedPriceDates, currentPrice));
        }
        return contexts;
    }

    private static Map<String, List<Splits>> splitsByTicker(Map<String, TickerContext> contexts) {
        Map<String, List<Splits>> splits = new HashMap<>();
        contexts.forEach((ticker, context) -> splits.put(ticker, context.splits()));
        return splits;
    }

    /** Yahoo raw closes and these balances both use today's split-adjusted share basis. */
    private Map<String, NavigableMap<LocalDate, BigDecimal>> quantityTimelines(
            Map<String, TickerContext> contexts, List<Transactions> allTx, LocalDate today) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> splitFactors = new HashMap<>();
        for (TickerContext context : contexts.values()) {
            NavigableMap<LocalDate, BigDecimal> ratios = new TreeMap<>();
            for (Splits split : context.splits()) {
                if (split == null || split.getSplitDate() == null || split.getRatioSplit() == null
                        || split.getRatioSplit().signum() <= 0 || split.getSplitDate().isAfter(today)) continue;
                ratios.put(split.getSplitDate(), split.getRatioSplit());
            }
            NavigableMap<LocalDate, BigDecimal> suffix = new TreeMap<>();
            BigDecimal factor = BigDecimal.ONE;
            for (Map.Entry<LocalDate, BigDecimal> entry : ratios.descendingMap().entrySet()) {
                factor = factor.multiply(entry.getValue());
                suffix.put(entry.getKey(), factor);
            }
            splitFactors.put(context.ticker(), suffix);
        }
        Map<String, NavigableMap<LocalDate, BigDecimal>> timelines = new HashMap<>();
        Map<String, BigDecimal> balances = new HashMap<>();
        for (Transactions tx : allTx) {
            if (!isTrade(tx) || tx.getTicker() == null || tx.getQuantity() == null || tx.getQuantity().signum() <= 0
                    || tx.getPrice() == null || tx.getPrice().signum() < 0) continue;
            NavigableMap<LocalDate, BigDecimal> factors = splitFactors.get(tx.getTicker());
            Map.Entry<LocalDate, BigDecimal> factor = factors == null ? null : factors.higherEntry(tx.getDate());
            BigDecimal quantity = tx.getQuantity().multiply(factor == null ? BigDecimal.ONE : factor.getValue());
            BigDecimal previous = balances.getOrDefault(tx.getTicker(), ZERO);
            BigDecimal balance = tx.getTransactionType() == TransactionType.BUY
                    ? previous.add(quantity) : previous.subtract(quantity).max(ZERO);
            balances.put(tx.getTicker(), balance);
            timelines.computeIfAbsent(tx.getTicker(), k -> new TreeMap<>()).put(tx.getDate(), balance);
        }
        return timelines;
    }

    private List<PerformancePoint> buildTimeSeries(Map<String, TickerContext> contexts,
                                                   List<Transactions> allTx, String period, LocalDate today) {
        LocalDate start = resolveStartDate(period, today, allTx);
        long days = today.toEpochDay() - start.toEpochDay();
        int count = (int) Math.min(days + 1, 200);
        Map<String, NavigableMap<LocalDate, BigDecimal>> timelines = quantityTimelines(contexts, allTx, today);
        List<PerformancePoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long offset = count <= 1 ? 0 : Math.round((double) i * days / (count - 1));
            points.add(valueOn(start.plusDays(offset), today, contexts, timelines));
        }
        return points;
    }

    private PerformancePoint valueOn(LocalDate date, LocalDate today, Map<String, TickerContext> contexts,
                                     Map<String, NavigableMap<LocalDate, BigDecimal>> timelines) {
        Map<String, BigDecimal> values = new TreeMap<>();
        List<String> estimatedPriceTickers = new ArrayList<>();
        for (TickerContext context : contexts.values()) {
            NavigableMap<LocalDate, BigDecimal> timeline = timelines.get(context.ticker());
            Map.Entry<LocalDate, BigDecimal> balance = timeline == null ? null : timeline.floorEntry(date);
            if (balance == null || balance.getValue().signum() <= 0) continue;
            BigDecimal price = context.priceOn(date, today);
            if (price != null && price.signum() >= 0) {
                values.merge(context.currency(), balance.getValue().multiply(price), BigDecimal::add);
                if (context.usesAdjustedCloseOn(date, today)) estimatedPriceTickers.add(context.ticker());
            }
        }
        Map<String, BigDecimal> rounded = scaled(values);
        return new PerformancePoint(date, money(sum(rounded)), rounded, List.copyOf(estimatedPriceTickers));
    }

    public List<PerformancePoint> getMonthlyHistory(String portfolioId) {
        LocalDate today = LocalDate.now();
        List<Transactions> allTx = transactionsAsOf(portfolioId, today);
        Map<String, TickerContext> contexts = loadTickerContexts(portfolioId,
                holdingsRepository.findAllByPortfolioId(portfolioId), allTx);
        Map<String, NavigableMap<LocalDate, BigDecimal>> timelines = quantityTimelines(contexts, allTx, today);
        YearMonth current = YearMonth.from(resolveStartDate("ALL", today, allTx));
        YearMonth end = YearMonth.from(today);
        List<PerformancePoint> points = new ArrayList<>();
        while (!current.isAfter(end)) {
            points.add(valueOn(current.equals(end) ? today : current.atEndOfMonth(), today, contexts, timelines));
            current = current.plusMonths(1);
        }
        return points;
    }

    private LocalDate resolveStartDate(String period, LocalDate end, List<Transactions> allTx) {
        return switch (period == null ? "ALL" : period.toUpperCase(Locale.ROOT)) {
            case "1W" -> end.minusWeeks(1);
            case "1M" -> end.minusMonths(1);
            case "3M" -> end.minusMonths(3);
            case "YTD" -> end.withDayOfYear(1);
            case "1Y" -> end.minusYears(1);
            default -> allTx.stream().map(Transactions::getDate).min(LocalDate::compareTo).orElse(end);
        };
    }

    private Map<String, BigDecimal> calcTotalInvestedByCurrency(List<Transactions> txs) {
        Map<String, BigDecimal> out = new TreeMap<>();
        for (Transactions tx : txs) {
            if (tx.getTransactionType() == TransactionType.BUY && tx.getPrice() != null && tx.getQuantity() != null) {
                out.merge(currencyOrUsd(tx.getCurrency()), tx.getPrice().multiply(tx.getQuantity())
                        .add(tx.getCommission() == null ? ZERO : tx.getCommission()), BigDecimal::add);
            }
        }
        return out;
    }

    private Map<String, BigDecimal> calcTotalDividendsByCurrency(List<Transactions> txs) {
        Map<String, BigDecimal> out = new TreeMap<>();
        for (Transactions tx : txs) {
            if (tx.getTransactionType() == TransactionType.DIVIDEND && tx.getAmount() != null) {
                out.merge(currencyOrUsd(tx.getCurrency()), tx.getAmount(), BigDecimal::add);
            }
        }
        return out;
    }

    private List<PerformanceData.CashFlow> cashFlows(List<Transactions> txs) {
        List<PerformanceData.CashFlow> flows = new ArrayList<>();
        for (Transactions tx : txs) {
            String currency = currencyOrUsd(tx.getCurrency());
            BigDecimal commission = tx.getCommission() == null ? ZERO : tx.getCommission();
            if (isTrade(tx) && tx.getPrice() != null && tx.getQuantity() != null) {
                BigDecimal gross = tx.getPrice().multiply(tx.getQuantity());
                BigDecimal amount = tx.getTransactionType() == TransactionType.BUY
                        ? gross.add(commission).negate() : gross.subtract(commission);
                flows.add(new PerformanceData.CashFlow(tx.getDate(), amount, currency));
            } else if (tx.getTransactionType() == TransactionType.DIVIDEND && tx.getAmount() != null) {
                flows.add(new PerformanceData.CashFlow(tx.getDate(), tx.getAmount(), currency));
            }
        }
        return flows;
    }

    /** Legacy native-currency XIRR; browser clients calculate after converting the cash flows. */
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
                double denom = Math.pow(1 + r, t);
                npv += cf[1] / denom;
                dnpv -= t * cf[1] / ((1 + r) * denom);
            }
            if (Math.abs(dnpv) < 1e-12) break;
            double next = r - npv / dnpv;
            if (Math.abs(next - r) < 1e-8) { r = next; break; }
            r = Math.max(next, -0.9999);
        }
        return BigDecimal.valueOf(r * 100).setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    private static boolean isTrade(Transactions tx) {
        return tx.getTransactionType() == TransactionType.BUY || tx.getTransactionType() == TransactionType.SELL;
    }

    private static boolean isMarket(Transactions tx) {
        return isTrade(tx) && (tx.getAssetType() == null || tx.getAssetType() == Assets.STOCK
                || tx.getAssetType() == Assets.CRYPTO);
    }

    private static BigDecimal sum(Map<String, BigDecimal> amounts) {
        return amounts.values().stream().reduce(ZERO, BigDecimal::add);
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal percent(BigDecimal amount, BigDecimal basis) {
        return basis.signum() == 0 ? money(ZERO)
                : money(amount.divide(basis, 6, RoundingMode.HALF_EVEN).multiply(BigDecimal.valueOf(100)));
    }

    private static Map<String, BigDecimal> scaled(Map<String, BigDecimal> amounts) {
        Map<String, BigDecimal> out = new TreeMap<>();
        amounts.forEach((c, v) -> out.put(c, money(v)));
        return out;
    }

    private static String currencyOrUsd(String currency) {
        return currency == null || currency.isBlank() ? "USD" : currency;
    }
}
