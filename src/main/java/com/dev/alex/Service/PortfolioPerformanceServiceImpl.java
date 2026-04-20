package com.dev.alex.Service;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.PerformanceData;
import com.dev.alex.Model.NonDbModel.PerformancePoint;
import com.dev.alex.Model.NonDbModel.PriceHistoryEntry;
import com.dev.alex.Model.PriceHistoryCache;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.PriceHistoryCacheRepository;
import com.dev.alex.Repository.TransactionsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int SCALE = 2;

    public PerformanceData getPerformance(String portfolioId, String period) {
        List<Transactions> allTx = transactionsRepository.findAllByPortfolioIdOrderByDateAsc(portfolioId);
        List<Holdings> holdings = holdingService.getAllHoldingsByPortfolioId(portfolioId);

        BigDecimal totalInvested = calcTotalInvested(allTx);
        BigDecimal totalDividends = calcTotalDividends(allTx);
        BigDecimal realizedPnL = calcRealizedPnL(allTx);

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
        List<PerformancePoint> timeSeries = buildTimeSeries(portfolioId, holdings, allTx, period);

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

    private BigDecimal calcRealizedPnL(List<Transactions> txList) {
        Map<String, Deque<BigDecimal[]>> buyLots = new HashMap<>();
        BigDecimal realized = ZERO;

        for (Transactions tx : txList) {
            String key = tx.getTicker() != null ? tx.getTicker() : tx.getName();
            if (key == null || tx.getQuantity() == null || tx.getPrice() == null) continue;

            if (tx.getTransactionType() == TransactionType.BUY) {
                BigDecimal commPerShare = commissionPerShare(tx);
                buyLots.computeIfAbsent(key, k -> new ArrayDeque<>())
                        .addLast(new BigDecimal[]{tx.getQuantity(), tx.getPrice(), commPerShare});

            } else if (tx.getTransactionType() == TransactionType.SELL) {
                Deque<BigDecimal[]> lots = buyLots.getOrDefault(key, new ArrayDeque<>());
                BigDecimal sellCommPerShare = commissionPerShare(tx);
                BigDecimal remaining = tx.getQuantity();

                while (remaining.compareTo(ZERO) > 0 && !lots.isEmpty()) {
                    BigDecimal[] lot = lots.peekFirst();
                    BigDecimal matched = remaining.min(lot[0]);
                    BigDecimal gain = tx.getPrice()
                            .subtract(lot[1])
                            .subtract(sellCommPerShare)
                            .subtract(lot[2])
                            .multiply(matched);
                    realized = realized.add(gain);
                    lot[0] = lot[0].subtract(matched);
                    remaining = remaining.subtract(matched);
                    if (lot[0].compareTo(ZERO) == 0) lots.pollFirst();
                }
            }
        }
        return realized;
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
                                                    List<Transactions> allTx, String period) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = resolveStartDate(period, endDate, allTx);

        // Load price history for stock-type holdings
        Map<String, Map<LocalDate, BigDecimal>> stockPrices = new HashMap<>();
        for (Holdings h : holdings) {
            if (h.getAssetType() != Assets.STOCK && h.getAssetType() != Assets.CRYPTO) continue;
            String ticker = h.getTicker();
            PriceHistoryCache cache = priceHistoryCacheRepository.findById(ticker).orElse(null);
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

        List<PerformancePoint> points = new ArrayList<>();
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            BigDecimal portfolioValue = ZERO;
            for (Holdings h : holdings) {
                String ticker = h.getTicker();
                BigDecimal qty = quantityAtDate(txByTicker.getOrDefault(ticker, List.of()), current);
                if (qty.compareTo(ZERO) <= 0) continue;

                BigDecimal price;
                if (h.getAssetType() == Assets.STOCK || h.getAssetType() == Assets.CRYPTO) {
                    price = priceOnOrBefore(stockPrices.get(ticker), current);
                } else {
                    price = flatPrices.get(ticker);
                }
                if (price != null && price.compareTo(ZERO) > 0) {
                    portfolioValue = portfolioValue.add(qty.multiply(price));
                }
            }
            points.add(new PerformancePoint(current, portfolioValue.setScale(SCALE, RoundingMode.HALF_EVEN)));
            current = current.plusDays(1);
        }

        return downsample(points, 200);
    }

    private BigDecimal quantityAtDate(List<Transactions> txForTicker, LocalDate date) {
        BigDecimal qty = ZERO;
        for (Transactions tx : txForTicker) {
            if (tx.getDate() == null || tx.getDate().isAfter(date)) continue;
            if (tx.getQuantity() == null) continue;
            if (tx.getTransactionType() == TransactionType.BUY) qty = qty.add(tx.getQuantity());
            else if (tx.getTransactionType() == TransactionType.SELL) qty = qty.subtract(tx.getQuantity());
        }
        return qty.max(ZERO);
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
}
