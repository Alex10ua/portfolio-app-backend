package com.dev.alex.Model.NonDbModel.Ai;

import com.dev.alex.Model.Enums.PriceUpdateMethod;
import com.dev.alex.Model.NonDbModel.PriceHistoryEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** A definition can exist without a holding; manual prices are estimates, not market quotes. */
public record AiCustomAsset(String id, String portfolioId, String ticker, String name, String assetType,
        String description, String country, String currency, String unit, BigDecimal priceNow,
        List<PriceHistoryEntry> priceHistory, int priceHistoryTotalCount, boolean historyTruncated,
        PriceUpdateMethod priceUpdateMethod, Map<String, String> customFields,
        LocalDate createdAt, LocalDate updatedAt) {}
