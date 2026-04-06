package com.dev.alex.Service.Interface;

import com.dev.alex.Model.CustomAsset;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CustomAssetService {
    CustomAsset create(String portfolioId, CustomAsset customAsset);
    List<CustomAsset> findAllByPortfolioId(String portfolioId);
    CustomAsset findByPortfolioIdAndTicker(String portfolioId, String ticker);
    Optional<CustomAsset> findOptionalByPortfolioIdAndTicker(String portfolioId, String ticker);
    CustomAsset update(String portfolioId, String ticker, CustomAsset updatedAsset);
    void delete(String portfolioId, String ticker);
    CustomAsset updatePrice(String portfolioId, String ticker, BigDecimal newPrice);
}
