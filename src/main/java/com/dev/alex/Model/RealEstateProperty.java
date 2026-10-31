package com.dev.alex.Model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Document("realEstateProperties")
@CompoundIndex(name = "properties_portfolio_status", def = "{'portfolioId':1,'status':1}")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RealEstateProperty {
    @Id private String id;
    private String portfolioId;
    private String name;
    private String propertyType;
    private String usage;
    private String currency;
    private String country;
    private String location;
    private LocalDate purchaseDate;
    private BigDecimal purchasePrice;
    private BigDecimal acquisitionCosts;
    private BigDecimal ownershipShare;
    private LocalDate trackingStartDate;
    private String financingStatus;
    private String status;
    private LocalDate archiveDate;
    private String notes;
    private List<Valuation> valuations = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    @Version private Long version;
    @JsonIgnore private String creationFingerprint;
    @JsonIgnore private List<RetryReceipt> retryReceipts = new ArrayList<>();

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class Valuation {
        private String id;
        private LocalDate effectiveDate;
        private BigDecimal fullPropertyValue;
        private String source;
        private String note;
        private Instant recordedAt;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class RetryReceipt {
        private String key;
        private String fingerprint;
    }
}
