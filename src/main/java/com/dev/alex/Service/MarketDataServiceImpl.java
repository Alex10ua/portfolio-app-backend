package com.dev.alex.Service;

import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.MarketStatistics;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.NonDbModel.TickerHistoricalData;
import com.dev.alex.Model.SharesOutstandingHistory;
import com.dev.alex.Model.SharesOutstandingHistory.SharesHistoryEntry;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Repository.SharesOutstandingHistoryRepository;
import com.dev.alex.Service.Interface.MarketDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class MarketDataServiceImpl implements MarketDataService {
    @Autowired
    private MarketDataRepository marketDataRepository;
    @Autowired
    private SharesOutstandingHistoryRepository sharesHistoryRepository;

    @Override
    public MarketData getMarketDataByTicker(String ticker) {
        return marketDataRepository.findByTicker(ticker);
    }

    @Override
    public void updatePriceByTicker(String ticker, BigDecimal price) {
        marketDataRepository.updatePriceByTicker(ticker, price);
    }

    @Description("get info for holding on Holding web-page")
    @Override
    public MarketData getMarketDataForHoldingsPage(String ticker) {
        return marketDataRepository.findByTickerForHoldingsPage(ticker);
    }

    @Description("batch fetch holdings-page market data for many tickers, keyed by ticker")
    @Override
    public java.util.Map<String, MarketData> getMarketDataForHoldingsPage(java.util.Collection<String> tickers) {
        java.util.Map<String, MarketData> byTicker = new java.util.HashMap<>();
        if (tickers == null || tickers.isEmpty()) return byTicker;
        for (MarketData md : marketDataRepository.findByTickerInForHoldingsPage(tickers)) {
            if (md.getTicker() != null) byTicker.put(md.getTicker(), md);
        }
        return byTicker;
    }

    @Override
    public void saveMarketData(MarketData marketData) {
        marketDataRepository.save(marketData);
    }

    @Description("Yahoo key statistics snapshot for the Statistics page")
    @Override
    public MarketStatistics getStatistics(String ticker) {
        MarketData data = marketDataRepository.findByTicker(ticker);
        if (data == null || data.getStatistics() == null) return null;
        MarketStatistics stats = data.getStatistics();
        // provider never writes currency into the sub-document (it would be wiped
        // on the next whole-object $set) — carry it over from the parent doc
        stats.setCurrency(data.getCurrency());
        return stats;
    }

    @Description("dividends + splits + share-count series for the Historical page")
    @Override
    public TickerHistoricalData getHistoricalData(String ticker) {
        MarketData data = marketDataRepository.findByTicker(ticker);
        if (data == null) return null;

        List<SharesHistoryEntry> shares = sharesHistoryRepository.findById(ticker)
                .map(SharesOutstandingHistory::getHistory)
                .map(entries -> sortedByDate(entries, SharesHistoryEntry::getDate))
                .orElseGet(List::of);

        return new TickerHistoricalData(
                data.getTicker(),
                data.getName(),
                data.getCurrency(),
                sortedByDate(data.getDividends(), Dividend::getDividendDate),
                sortedByDate(data.getSplits(), Splits::getSplitDate),
                shares
        );
    }

    /**
     * Ascending by date, entries with no date dropped: dividend/split lists are
     * provider-merged maps (see _merge_list in updateMarketData.py) so Mongo
     * returns them in insertion order, not chronologically.
     */
    private <T> List<T> sortedByDate(List<T> entries, Function<T, LocalDate> dateOf) {
        if (entries == null) return List.of();
        return entries.stream()
                .filter(e -> e != null && dateOf.apply(e) != null)
                .sorted(Comparator.comparing(dateOf))
                .collect(Collectors.toList());
    }
}
