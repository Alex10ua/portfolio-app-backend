package com.dev.alex.Service;

import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.PriceHistoryEntry;
import com.dev.alex.Repository.CustomAssetRepository;
import com.dev.alex.Repository.MarketDataRepository;
import com.dev.alex.Service.Interface.CustomAssetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class CustomAssetServiceImpl implements CustomAssetService {

    @Autowired
    private CustomAssetRepository customAssetRepository;

    @Autowired
    private MarketDataRepository marketDataRepository;

    @Override
    public CustomAsset create(String portfolioId, CustomAsset customAsset) {
        if (customAsset.getTicker() == null || customAsset.getTicker().isBlank()) {
            throw new IllegalArgumentException("Ticker is required");
        }
        String ticker = customAsset.getTicker().toUpperCase();
        if (customAssetRepository.existsByPortfolioIdAndTicker(portfolioId, ticker)) {
            throw new IllegalArgumentException("Custom asset with ticker '" + ticker + "' already exists in this portfolio");
        }
        customAsset.setId(UUID.randomUUID().toString());
        customAsset.setPortfolioId(portfolioId);
        customAsset.setTicker(ticker);
        customAsset.setCreatedAt(LocalDate.now());
        customAsset.setUpdatedAt(LocalDate.now());

        // Create corresponding MarketData entry so holdings calculations work
        MarketData existing = marketDataRepository.findByTicker(ticker);
        if (existing == null) {
            MarketData marketData = new MarketData();
            marketData.setTicker(ticker);
            marketData.setName(customAsset.getName());
            marketData.setPrice(customAsset.getPriceNow() != null ? customAsset.getPriceNow() : BigDecimal.ZERO);
            marketData.setUpdatedAt(LocalDate.now());
            marketDataRepository.save(marketData);
        }

        return customAssetRepository.save(customAsset);
    }

    @Override
    public List<CustomAsset> findAllByPortfolioId(String portfolioId) {
        return customAssetRepository.findAllByPortfolioId(portfolioId);
    }

    @Override
    public CustomAsset findByPortfolioIdAndTicker(String portfolioId, String ticker) {
        return customAssetRepository.findByPortfolioIdAndTicker(portfolioId, ticker.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Custom asset not found: " + ticker));
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
        existing.setUpdatedAt(LocalDate.now());
        return customAssetRepository.save(existing);
    }

    @Override
    public void delete(String portfolioId, String ticker) {
        CustomAsset existing = findByPortfolioIdAndTicker(portfolioId, ticker);
        customAssetRepository.delete(existing);
    }

    @Override
    public CustomAsset updatePrice(String portfolioId, String ticker, BigDecimal newPrice) {
        CustomAsset asset = findByPortfolioIdAndTicker(portfolioId, ticker);

        // Append old price to history before overwriting
        if (asset.getPriceNow() != null) {
            asset.getPriceHistory().add(new PriceHistoryEntry(LocalDate.now(), asset.getPriceNow()));
        }
        asset.setPriceNow(newPrice);
        asset.setUpdatedAt(LocalDate.now());

        // Keep MarketData in sync so HoldingsCompleteData picks up the new price
        MarketData marketData = marketDataRepository.findByTicker(ticker.toUpperCase());
        if (marketData != null) {
            marketData.setPriceYesterday(marketData.getPrice());
            marketData.setPrice(newPrice);
            marketData.setUpdatedAt(LocalDate.now());
            marketDataRepository.save(marketData);
        }

        return customAssetRepository.save(asset);
    }

    public CustomAsset bulkMergePriceHistory(String portfolioId, String ticker, List<PriceHistoryEntry> entries) {
        CustomAsset asset = findByPortfolioIdAndTicker(portfolioId, ticker);

        // Merge existing + new entries by date (new entries overwrite on same date)
        Map<LocalDate, BigDecimal> historyMap = new LinkedHashMap<>();
        for (PriceHistoryEntry e : asset.getPriceHistory()) {
            if (e.getDate() != null) historyMap.put(e.getDate(), e.getPrice());
        }
        LocalDate latestDate = null;
        BigDecimal latestPrice = null;
        for (PriceHistoryEntry e : entries) {
            if (e.getDate() == null || e.getPrice() == null) continue;
            historyMap.put(e.getDate(), e.getPrice());
            if (latestDate == null || e.getDate().isAfter(latestDate)) {
                latestDate = e.getDate();
                latestPrice = e.getPrice();
            }
        }

        List<PriceHistoryEntry> merged = historyMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new PriceHistoryEntry(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        asset.setPriceHistory(merged);

        if (latestPrice != null) {
            asset.setPriceNow(latestPrice);
            asset.setUpdatedAt(latestDate);

            MarketData marketData = marketDataRepository.findByTicker(ticker.toUpperCase());
            if (marketData != null) {
                marketData.setPriceYesterday(marketData.getPrice());
                marketData.setPrice(latestPrice);
                marketData.setUpdatedAt(latestDate);
                marketDataRepository.save(marketData);
            }
        }

        return customAssetRepository.save(asset);
    }
}
