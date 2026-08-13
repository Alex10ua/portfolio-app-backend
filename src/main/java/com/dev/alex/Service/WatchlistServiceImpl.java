package com.dev.alex.Service;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.MonthlyPriceHistory;
import com.dev.alex.Model.MonthlyPriceHistory.MonthlyPricePoint;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.TickerSuggestion;
import com.dev.alex.Model.NonDbModel.WatchlistEntry;
import com.dev.alex.Model.NonDbModel.WatchlistEntry.YieldPoint;
import com.dev.alex.Model.WatchlistItem;
import com.dev.alex.Repository.CustomAssetRepository;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Repository.MonthlyPriceHistoryRepository;
import com.dev.alex.Repository.WatchlistRepository;
import com.dev.alex.Service.Interface.WatchlistService;
import com.dev.alex.Service.WebCalls.FlaskClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class WatchlistServiceImpl implements WatchlistService {

    /** Same shape FlaskClientService/WebClientConfig accept — anything else never reaches a provider. */
    private static final Pattern TICKER = Pattern.compile("^[A-Za-z0-9.\\-=^]{1,15}$");
    /** Months of yield history returned; 20 years, double what the widest UI timeframe reads. */
    private static final int MAX_MONTHS = 240;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Autowired
    private WatchlistRepository watchlistRepository;
    @Autowired
    private MarketDataRepository marketDataRepository;
    @Autowired
    private MonthlyPriceHistoryRepository monthlyPriceHistoryRepository;
    @Autowired
    private HoldingsRepository holdingsRepository;
    @Autowired
    private CustomAssetRepository customAssetRepository;
    @Autowired
    private FlaskClientService flaskClientService;

    // ---------- QUERIES ----------

    @Override
    public List<WatchlistEntry> getWatchlist(String portfolioId) {
        List<WatchlistItem> items = watchlistRepository.findAllByPortfolioId(portfolioId);
        if (items.isEmpty()) return List.of();

        List<String> tickers = items.stream().map(WatchlistItem::getTicker).collect(Collectors.toList());
        Map<String, MarketData> marketByTicker = marketDataRepository.findAllByTickerIn(tickers).stream()
                .filter(md -> md.getTicker() != null)
                .collect(Collectors.toMap(MarketData::getTicker, md -> md, (a, b) -> a));
        Map<String, MonthlyPriceHistory> historyByTicker = monthlyPriceHistoryRepository.findMonthlyByTickerIn(tickers)
                .stream().collect(Collectors.toMap(MonthlyPriceHistory::getTicker, h -> h, (a, b) -> a));
        Set<String> heldTickers = holdingsRepository.findAllByPortfolioId(portfolioId).stream()
                .map(Holdings::getTicker).collect(Collectors.toSet());

        return items.stream()
                .map(item -> toEntry(item,
                        marketByTicker.get(item.getTicker()),
                        historyByTicker.get(item.getTicker()),
                        heldTickers.contains(item.getTicker())))
                .sorted(Comparator.comparing(WatchlistEntry::getTicker))
                .collect(Collectors.toList());
    }

    @Override
    public List<TickerSuggestion> search(String portfolioId, String query) {
        String cleaned = query == null ? "" : query.trim().replaceAll("[^A-Za-z0-9.\\-^ ]", "");
        if (cleaned.length() < 1) return List.of();

        Set<String> watched = watchlistRepository.findAllByPortfolioId(portfolioId).stream()
                .map(WatchlistItem::getTicker).collect(Collectors.toSet());
        Set<String> customTickers = customAssetRepository.findAllByPortfolioId(portfolioId).stream()
                .map(ca -> ca.getTicker()).collect(Collectors.toSet());

        return marketDataRepository.searchByTickerOrName(Pattern.quote(cleaned), PageRequest.of(0, 25)).stream()
                .filter(md -> md.getTicker() != null && !customTickers.contains(md.getTicker()))
                .map(md -> new TickerSuggestion(
                        md.getTicker(), md.getName(), md.getSector(), md.getCurrency(), md.getPrice(),
                        yieldOf(forwardDividend(md), md.getPrice()),
                        watched.contains(md.getTicker())))
                .limit(8)
                .collect(Collectors.toList());
    }

    @Override
    public WatchlistEntry preview(String portfolioId, String ticker) {
        String symbol = normalize(ticker);
        MarketData market = marketDataRepository.findByTicker(symbol);
        if (market == null || market.getPrice() == null) return null;

        MonthlyPriceHistory history = monthlyPriceHistoryRepository.findById(symbol).orElse(null);
        WatchlistItem draft = new WatchlistItem(null, portfolioId, symbol, defaultTargetYield(market, history), null);
        return toEntry(draft, market, history, isHeld(portfolioId, symbol));
    }

    // ---------- MUTATIONS ----------

    @Override
    public WatchlistEntry addTicker(String portfolioId, String ticker, BigDecimal targetYield) {
        String symbol = normalize(ticker);
        if (watchlistRepository.findByPortfolioIdAndTicker(portfolioId, symbol).isPresent()) {
            throw new IllegalArgumentException(symbol + " is already on this watchlist");
        }
        if (customAssetRepository.existsByPortfolioIdAndTicker(portfolioId, symbol)) {
            throw new IllegalArgumentException(symbol + " is a custom asset — its price is user-set, so it has no market yield history");
        }

        MarketData market = marketDataRepository.findByTicker(symbol);
        MonthlyPriceHistory history = monthlyPriceHistoryRepository.findById(symbol).orElse(null);
        // A ticker nobody holds has usually never been fetched. One /update/full brings
        // price, dividends, statistics and both price series in a single provider call.
        if (market == null || market.getPrice() == null || history == null
                || history.getMonthlyHistory() == null || history.getMonthlyHistory().isEmpty()) {
            fetchFromProvider(symbol);
            market = marketDataRepository.findByTicker(symbol);
            history = monthlyPriceHistoryRepository.findById(symbol).orElse(null);
        }
        if (market == null || market.getPrice() == null) {
            throw new IllegalArgumentException("No market data for " + symbol + " — check the symbol as the provider spells it");
        }

        BigDecimal target = targetYield != null && targetYield.signum() > 0
                ? targetYield
                : defaultTargetYield(market, history);

        WatchlistItem item = new WatchlistItem(null, portfolioId, symbol, target, LocalDate.now());
        watchlistRepository.save(item);
        return toEntry(item, market, history, isHeld(portfolioId, symbol));
    }

    @Override
    public WatchlistEntry setTargetYield(String portfolioId, String ticker, BigDecimal targetYield) {
        String symbol = normalize(ticker);
        if (targetYield == null || targetYield.signum() <= 0) {
            throw new IllegalArgumentException("Target yield must be greater than 0");
        }
        WatchlistItem item = requireItem(portfolioId, symbol);
        item.setTargetYield(targetYield);
        watchlistRepository.save(item);
        return toEntry(item, marketDataRepository.findByTicker(symbol),
                monthlyPriceHistoryRepository.findById(symbol).orElse(null), isHeld(portfolioId, symbol));
    }

    @Override
    public void removeTicker(String portfolioId, String ticker) {
        watchlistRepository.deleteByPortfolioIdAndTicker(portfolioId, normalize(ticker));
    }

    @Override
    public WatchlistEntry refreshTicker(String portfolioId, String ticker) {
        String symbol = normalize(ticker);
        WatchlistItem item = requireItem(portfolioId, symbol);
        fetchFromProvider(symbol);
        return toEntry(item, marketDataRepository.findByTicker(symbol),
                monthlyPriceHistoryRepository.findById(symbol).orElse(null), isHeld(portfolioId, symbol));
    }

    // ---------- ASSEMBLY ----------

    private WatchlistEntry toEntry(WatchlistItem item, MarketData market, MonthlyPriceHistory history, boolean held) {
        WatchlistEntry entry = new WatchlistEntry();
        entry.setTicker(item.getTicker());
        entry.setTargetYield(item.getTargetYield());
        entry.setAddedAt(item.getAddedAt());
        entry.setHeld(held);
        entry.setYieldHistory(List.of());

        if (market == null) return entry;

        entry.setName(market.getName());
        entry.setCurrency(market.getCurrency());
        entry.setSector(market.getSector());
        entry.setPrice(market.getPrice());
        entry.setPriceYesterday(market.getPriceYesterday());
        entry.setPriceUpdatedAt(market.getUpdatedAt());
        entry.setDayChangePercent(changePercent(market.getPriceYesterday(), market.getPrice()));

        BigDecimal forwardDividend = forwardDividend(market);
        entry.setForwardDividend(forwardDividend);
        entry.setDividendFrequency(frequency(market.getDividends()));
        BigDecimal forwardYield = yieldOf(forwardDividend, market.getPrice());
        entry.setForwardYield(forwardYield);
        entry.setExDividendDate(exDividendDate(market));
        entry.setYieldHistory(yieldHistory(market.getDividends(), history));

        BigDecimal target = item.getTargetYield();
        if (target != null && target.signum() > 0 && forwardDividend != null && forwardDividend.signum() > 0) {
            BigDecimal buyBelow = forwardDividend.multiply(HUNDRED).divide(target, 4, RoundingMode.HALF_UP);
            entry.setBuyBelowPrice(buyBelow);
            entry.setToTargetPercent(changePercent(market.getPrice(), buyBelow));
        }
        entry.setAtTarget(forwardYield != null && target != null && forwardYield.compareTo(target) >= 0);
        return entry;
    }

    /**
     * Monthly trailing-twelve-month yield: the dividends of the twelve months
     * ending in that month over that month's close.
     *
     * The close used is rawPrice — the quoted one. `price` is total-return
     * adjusted (back-scaled by every dividend paid since), which would make every
     * historical yield read richer the further back it sits. Documents written
     * before rawPrice existed have only the adjusted series, so they fall back to
     * it and read slightly high until the ticker is refreshed.
     */
    private List<YieldPoint> yieldHistory(List<Dividend> dividends, MonthlyPriceHistory history) {
        if (history == null || history.getMonthlyHistory() == null || dividends == null || dividends.isEmpty()) {
            return List.of();
        }
        Map<YearMonth, BigDecimal> paidByMonth = new HashMap<>();
        YearMonth firstPayment = null;
        for (Dividend dividend : dividends) {
            if (dividend == null || dividend.getDividendDate() == null || dividend.getDividendAmount() == null) continue;
            YearMonth month = YearMonth.from(dividend.getDividendDate());
            paidByMonth.merge(month, dividend.getDividendAmount(), BigDecimal::add);
            if (firstPayment == null || month.isBefore(firstPayment)) firstPayment = month;
        }
        if (firstPayment == null) return List.of();
        // A window that starts before the first payment is not a yield, it is a
        // partially-collected year — it would read as a dividend cut that never happened.
        YearMonth firstComplete = firstPayment.plusMonths(11);

        List<YieldPoint> points = new ArrayList<>();
        for (MonthlyPricePoint point : history.getMonthlyHistory()) {
            YearMonth month = parseMonth(point.getDate());
            if (month == null || month.isBefore(firstComplete)) continue;

            // a NaN/absent close is a month the provider had no print for, not a zero
            Double quoted = point.getRawPrice() != null ? point.getRawPrice() : point.getPrice();
            if (quoted == null || !Double.isFinite(quoted) || quoted <= 0) continue;
            BigDecimal close = BigDecimal.valueOf(quoted);

            BigDecimal trailing = BigDecimal.ZERO;
            for (int back = 0; back < 12; back++) {
                trailing = trailing.add(paidByMonth.getOrDefault(month.minusMonths(back), BigDecimal.ZERO));
            }
            if (trailing.signum() <= 0) continue;

            points.add(new YieldPoint(month.toString(),
                    trailing.multiply(HUNDRED).divide(close, 4, RoundingMode.HALF_UP)));
        }
        return points.size() > MAX_MONTHS ? points.subList(points.size() - MAX_MONTHS, points.size()) : points;
    }

    /**
     * Forward annual dividend per share: Yahoo's own forward rate first, then the
     * trailing-year figure the provider reports, then the payments on record.
     */
    private BigDecimal forwardDividend(MarketData market) {
        if (market == null) return null;
        if (market.getStatistics() != null && isPositive(market.getStatistics().getDividendRate())) {
            return market.getStatistics().getDividendRate();
        }
        if (isPositive(market.getYearlyDividend())) return market.getYearlyDividend();

        BigDecimal trailing = BigDecimal.ZERO;
        LocalDate cutoff = LocalDate.now().minusYears(1);
        for (Dividend dividend : dividendsOf(market)) {
            if (dividend.getDividendDate() != null && dividend.getDividendDate().isAfter(cutoff)
                    && dividend.getDividendAmount() != null) {
                trailing = trailing.add(dividend.getDividendAmount());
            }
        }
        return trailing.signum() > 0 ? trailing : null;
    }

    /** Payment cadence read off the last year of payments, not off a provider field. */
    private String frequency(List<Dividend> dividends) {
        if (dividends == null || dividends.isEmpty()) return null;
        LocalDate cutoff = LocalDate.now().minusYears(1);
        long payments = dividends.stream()
                .filter(d -> d != null && d.getDividendDate() != null && d.getDividendDate().isAfter(cutoff))
                .count();
        if (payments >= 10) return "Monthly";
        if (payments >= 3) return "Quarterly";
        if (payments == 2) return "Semi-annual";
        if (payments == 1) return "Annual";
        return "Irregular";
    }

    private LocalDate exDividendDate(MarketData market) {
        if (market.getStatistics() != null && market.getStatistics().getExDividendDate() != null) {
            return market.getStatistics().getExDividendDate();
        }
        return dividendsOf(market).stream()
                .map(Dividend::getExDividendDate)
                .filter(java.util.Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
    }

    /** The mockup's default: buy at a yield the ticker only reached in its richest 10% of months. */
    private BigDecimal defaultTargetYield(MarketData market, MonthlyPriceHistory history) {
        List<YieldPoint> points = yieldHistory(market.getDividends(), history);
        if (!points.isEmpty()) {
            List<BigDecimal> window = points.subList(Math.max(0, points.size() - 60), points.size()).stream()
                    .map(YieldPoint::getYield).sorted().collect(Collectors.toList());
            BigDecimal p90 = quantile(window, 0.90);
            if (p90 != null && p90.signum() > 0) return p90.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal current = yieldOf(forwardDividend(market), market.getPrice());
        return current != null && current.signum() > 0 ? current.setScale(2, RoundingMode.HALF_UP) : null;
    }

    // ---------- HELPERS ----------

    private void fetchFromProvider(String ticker) {
        try {
            flaskClientService.refreshFull(ticker);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not fetch market data for " + ticker + ": " + e.getMessage());
        }
    }

    private WatchlistItem requireItem(String portfolioId, String ticker) {
        return watchlistRepository.findByPortfolioIdAndTicker(portfolioId, ticker)
                .orElseThrow(() -> new IllegalArgumentException(ticker + " is not on this watchlist"));
    }

    private boolean isHeld(String portfolioId, String ticker) {
        return holdingsRepository.findByPortfolioIdAndTicker(portfolioId, ticker) != null;
    }

    private String normalize(String ticker) {
        String symbol = ticker == null ? "" : ticker.trim().toUpperCase();
        if (!TICKER.matcher(symbol).matches()) {
            throw new IllegalArgumentException("'" + ticker + "' is not a valid ticker symbol");
        }
        return symbol;
    }

    private List<Dividend> dividendsOf(MarketData market) {
        return market.getDividends() == null ? List.of() : market.getDividends();
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private BigDecimal yieldOf(BigDecimal annualDividend, BigDecimal price) {
        if (!isPositive(annualDividend) || !isPositive(price)) return null;
        return annualDividend.multiply(HUNDRED).divide(price, 4, RoundingMode.HALF_UP);
    }

    /** Percent move from `from` to `to`. */
    private BigDecimal changePercent(BigDecimal from, BigDecimal to) {
        if (!isPositive(from) || to == null) return null;
        return to.subtract(from).multiply(HUNDRED).divide(from, 4, RoundingMode.HALF_UP);
    }

    /** Linear-interpolated quantile of an ascending list — matches the client's own. */
    private BigDecimal quantile(List<BigDecimal> ascending, double q) {
        if (ascending == null || ascending.isEmpty()) return null;
        double position = (ascending.size() - 1) * q;
        int low = (int) Math.floor(position);
        int high = (int) Math.ceil(position);
        BigDecimal lowValue = ascending.get(low);
        if (low == high) return lowValue;
        return lowValue.add(ascending.get(high).subtract(lowValue)
                .multiply(BigDecimal.valueOf(position - low)));
    }

    private YearMonth parseMonth(String date) {
        if (date == null || date.length() < 7) return null;
        try {
            return YearMonth.parse(date.substring(0, 7));
        } catch (Exception e) {
            return null;
        }
    }
}
