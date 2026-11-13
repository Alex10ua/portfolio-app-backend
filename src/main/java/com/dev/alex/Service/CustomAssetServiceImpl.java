package com.dev.alex.Service;

import com.dev.alex.Config.AppClock;
import com.dev.alex.Config.NotFoundException;
import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.PriceHistoryEntry;
import com.dev.alex.Model.Holdings;
import com.dev.alex.Repository.CustomAssetRepository;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Service.Interface.CustomAssetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional
public class CustomAssetServiceImpl implements CustomAssetService {

    /**
     * Characters a URL path cannot carry. The ticker is a path segment of every custom-asset
     * endpoint, and Spring's firewall rejects an encoded '/', '\', '%' or ';' outright, so an
     * asset named "GOLD 1/2 OZ" could be created but never priced, edited or deleted.
     */
    private static final Pattern UNSAFE_TICKER = Pattern.compile("[/\\\\?#%;\\p{Cntrl}]");
    private static final int MAX_TICKER_LENGTH = 64;

    @Autowired
    private CustomAssetRepository customAssetRepository;

    @Autowired
    private MarketDataRepository marketDataRepository;

    @Autowired
    private HoldingsRepository holdingsRepository;

    @Override
    public CustomAsset create(String portfolioId, CustomAsset customAsset) {
        if (customAsset.getTicker() == null || customAsset.getTicker().isBlank()) {
            throw new IllegalArgumentException("Ticker is required");
        }
        String ticker = customAsset.getTicker().trim().toUpperCase();
        if (ticker.length() > MAX_TICKER_LENGTH || UNSAFE_TICKER.matcher(ticker).find()) {
            throw new IllegalArgumentException("Ticker must be at most " + MAX_TICKER_LENGTH
                    + " characters and must not contain / \\ ? # % or ;");
        }
        if (customAssetRepository.existsByPortfolioIdAndTicker(portfolioId, ticker)) {
            throw new IllegalArgumentException("Custom asset with ticker '" + ticker + "' already exists in this portfolio");
        }
        LocalDate today = AppClock.today();
        customAsset.setId(UUID.randomUUID().toString());
        customAsset.setPortfolioId(portfolioId);
        customAsset.setTicker(ticker);
        customAsset.setCreatedAt(today);
        customAsset.setUpdatedAt(today);

        // Create corresponding MarketData entry so holdings calculations work
        MarketData existing = marketDataRepository.findByTicker(ticker);
        if (existing == null) {
            MarketData marketData = new MarketData();
            marketData.setTicker(ticker);
            marketData.setName(customAsset.getName());
            marketData.setPrice(customAsset.getPriceNow() != null ? customAsset.getPriceNow() : BigDecimal.ZERO);
            marketData.setUpdatedAt(today);
            marketDataRepository.save(marketData);
        }

        return customAssetRepository.save(customAsset);
    }

    @Override
    public List<CustomAsset> findAllByPortfolioId(String portfolioId) {
        return customAssetRepository.findAllByPortfolioId(portfolioId);
    }

    public Map<String, CustomAsset> findForDiversification(String portfolioId, Collection<String> tickers) {
        Map<String, CustomAsset> byTicker = new HashMap<>();
        if (tickers == null || tickers.isEmpty()) return byTicker;
        for (CustomAsset asset : customAssetRepository.findForDiversification(portfolioId, tickers)) {
            if (asset.getTicker() != null) byTicker.put(asset.getTicker(), asset);
        }
        return byTicker;
    }

    @Override
    public CustomAsset findByPortfolioIdAndTicker(String portfolioId, String ticker) {
        return customAssetRepository.findByPortfolioIdAndTicker(portfolioId, ticker.toUpperCase())
                .orElseThrow(() -> new NotFoundException("Custom asset not found: " + ticker));
    }

    @Override
    public Optional<CustomAsset> findOptionalByPortfolioIdAndTicker(String portfolioId, String ticker) {
        return customAssetRepository.findByPortfolioIdAndTicker(portfolioId, ticker.toUpperCase());
    }

    @Override
    public CustomAsset update(String portfolioId, String ticker, CustomAsset updatedAsset) {
        CustomAsset existing = findByPortfolioIdAndTicker(portfolioId, ticker);
        if (updatedAsset.getName() != null) existing.setName(updatedAsset.getName());
        if (updatedAsset.getAssetType() != null) existing.setAssetType(updatedAsset.getAssetType());
        if (updatedAsset.getDescription() != null) existing.setDescription(updatedAsset.getDescription());
        if (updatedAsset.getCountry() != null) existing.setCountry(updatedAsset.getCountry());
        if (updatedAsset.getCurrency() != null) existing.setCurrency(updatedAsset.getCurrency());
        if (updatedAsset.getUnit() != null) existing.setUnit(updatedAsset.getUnit());
        if (updatedAsset.getPriceUpdateMethod() != null) existing.setPriceUpdateMethod(updatedAsset.getPriceUpdateMethod());
        if (updatedAsset.getCustomFields() != null) existing.setCustomFields(updatedAsset.getCustomFields());
        existing.setUpdatedAt(AppClock.today());

        // Holdings table reads name/currency from marketData — keep the stub in sync
        if (updatedAsset.getName() != null || updatedAsset.getCurrency() != null) {
            MarketData marketData = marketDataRepository.findByTicker(existing.getTicker().toUpperCase());
            if (marketData != null) {
                if (updatedAsset.getName() != null) marketData.setName(updatedAsset.getName());
                if (updatedAsset.getCurrency() != null) marketData.setCurrency(updatedAsset.getCurrency());
                marketDataRepository.save(marketData);
            }
        }
        return customAssetRepository.save(existing);
    }

    @Override
    public void delete(String portfolioId, String ticker) {
        CustomAsset existing = findByPortfolioIdAndTicker(portfolioId, ticker);
        customAssetRepository.delete(existing);
    }

    @Override
    public CustomAsset updatePrice(String portfolioId, String ticker, BigDecimal newPrice) {
        return updatePrice(portfolioId, ticker, newPrice, null);
    }

    /**
     * Records the price at the given date (null = today). A date before the current price's
     * date only merges into priceHistory (overwriting a same-date entry); a date on or after it
     * also becomes priceNow and syncs MarketData and the holding.
     */
    @Override
    public CustomAsset updatePrice(String portfolioId, String ticker, BigDecimal newPrice, LocalDate date) {
        CustomAsset asset = findByPortfolioIdAndTicker(portfolioId, ticker);
        LocalDate today = AppClock.today();
        LocalDate effectiveDate = (date != null) ? date : today;
        if (effectiveDate.isAfter(today)) {
            throw new IllegalArgumentException("Price date cannot be in the future");
        }

        // Merge into history by date — same-date update overwrites instead of duplicating
        TreeMap<LocalDate, BigDecimal> historyMap = historyOf(asset);
        LocalDate priceDate = currentPriceDate(asset, historyMap);
        boolean isCurrent = priceDate == null || !effectiveDate.isBefore(priceDate);

        if (isCurrent) {
            // Preserve the old current price in history under its own date
            if (asset.getPriceNow() != null && priceDate != null && !priceDate.equals(effectiveDate)) {
                historyMap.putIfAbsent(priceDate, asset.getPriceNow());
            }
            asset.setPriceNow(newPrice);
            syncMarketPrice(asset.getTicker(), newPrice, effectiveDate);
            syncHoldingPrice(portfolioId, asset.getTicker(), newPrice);
        }
        historyMap.put(effectiveDate, newPrice);
        asset.setPriceHistory(toEntries(historyMap));
        asset.setUpdatedAt(today);
        return customAssetRepository.save(asset);
    }

    /**
     * Merges price points by date (new entries overwrite the same date). The current price only
     * moves when the merged history now ends later than it did, or when the entry for the current
     * date was corrected: importing an older statement used to reset priceNow, MarketData and
     * the holding to that statement's last price.
     */
    public CustomAsset bulkMergePriceHistory(String portfolioId, String ticker, List<PriceHistoryEntry> entries) {
        CustomAsset asset = findByPortfolioIdAndTicker(portfolioId, ticker);
        TreeMap<LocalDate, BigDecimal> historyMap = historyOf(asset);
        LocalDate previousDate = currentPriceDate(asset, historyMap);

        for (PriceHistoryEntry e : entries == null ? List.<PriceHistoryEntry>of() : entries) {
            if (e == null || e.getDate() == null || e.getPrice() == null) continue;
            historyMap.put(e.getDate(), e.getPrice());
        }
        asset.setPriceHistory(toEntries(historyMap));

        Map.Entry<LocalDate, BigDecimal> latest = historyMap.lastEntry();
        if (latest != null) {
            boolean newer = previousDate == null || latest.getKey().isAfter(previousDate);
            boolean corrected = latest.getKey().equals(previousDate)
                    && (asset.getPriceNow() == null || latest.getValue().compareTo(asset.getPriceNow()) != 0);
            if (newer || corrected) {
                asset.setPriceNow(latest.getValue());
                syncMarketPrice(asset.getTicker(), latest.getValue(), latest.getKey());
                syncHoldingPrice(portfolioId, asset.getTicker(), latest.getValue());
            }
        }
        asset.setUpdatedAt(AppClock.today());
        return customAssetRepository.save(asset);
    }

    /**
     * The date priceNow belongs to: the newest history point, or the creation day for an asset
     * whose price was never updated (create() records no history point). updatedAt is not it —
     * a name or unit edit stamps that too, which made a back-dated price that was in fact the
     * newest one land in history only, and filed the old price under the edit date.
     */
    private static LocalDate currentPriceDate(CustomAsset asset, NavigableMap<LocalDate, BigDecimal> history) {
        if (!history.isEmpty()) return history.lastKey();
        return asset.getCreatedAt() != null ? asset.getCreatedAt() : asset.getUpdatedAt();
    }

    private static TreeMap<LocalDate, BigDecimal> historyOf(CustomAsset asset) {
        TreeMap<LocalDate, BigDecimal> history = new TreeMap<>();
        if (asset.getPriceHistory() != null) {
            for (PriceHistoryEntry e : asset.getPriceHistory()) {
                if (e != null && e.getDate() != null && e.getPrice() != null) history.put(e.getDate(), e.getPrice());
            }
        }
        return history;
    }

    private static List<PriceHistoryEntry> toEntries(Map<LocalDate, BigDecimal> history) {
        return history.entrySet().stream()
                .map(e -> new PriceHistoryEntry(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    /** Keep MarketData in sync so HoldingsCompleteData picks up the new price. */
    private void syncMarketPrice(String ticker, BigDecimal price, LocalDate asOf) {
        MarketData marketData = marketDataRepository.findByTicker(ticker.toUpperCase());
        if (marketData != null) {
            marketData.setPriceYesterday(marketData.getPrice());
            marketData.setPrice(price);
            marketData.setUpdatedAt(asOf);
            marketDataRepository.save(marketData);
        }
    }

    /**
     * Holdings.priceNow is written when the holding is created and was never touched again, so
     * anything reading it (the Performance page did) kept the creation-day price for good.
     */
    private void syncHoldingPrice(String portfolioId, String ticker, BigDecimal price) {
        Holdings holding = holdingsRepository.findByPortfolioIdAndTicker(portfolioId, ticker.toUpperCase());
        if (holding != null) {
            holding.setPriceNow(price);
            holdingsRepository.save(holding);
        }
    }
}
