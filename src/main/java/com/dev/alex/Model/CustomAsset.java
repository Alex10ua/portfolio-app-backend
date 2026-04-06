package com.dev.alex.Model;

import com.dev.alex.Model.Enums.PriceUpdateMethod;
import com.dev.alex.Model.NonDbModel.PriceHistoryEntry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Document("customAssets")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class CustomAsset {
    @Id
    private String id;
    private String portfolioId;
    private String ticker;          // unique per portfolio, user-defined ID (e.g. "SILV-EAGLE-1OZ")
    private String name;            // display name (e.g. "Silver Eagle 1oz")
    private String assetType;       // user-defined category label (e.g. "COIN", "FIGURE", "WINE")
    private String description;
    private String country;
    private String currency;
    private String unit;            // e.g. "pcs", "oz", "bottle"
    private BigDecimal priceNow;
    private List<PriceHistoryEntry> priceHistory = new ArrayList<>();
    private PriceUpdateMethod priceUpdateMethod;
    private Map<String, String> customFields = new HashMap<>();
    private LocalDate createdAt;
    private LocalDate updatedAt;
}
