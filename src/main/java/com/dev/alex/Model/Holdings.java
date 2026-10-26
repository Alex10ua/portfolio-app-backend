package com.dev.alex.Model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;

import com.dev.alex.Model.Enums.Assets;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("holdings")
@CompoundIndex(name = "holdings_portfolio_ticker", def = "{'portfolioId': 1, 'ticker': 1}")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class Holdings {
    @Id
    private String holdingId;
    private String portfolioId;
    private Assets assetType;
    private String ticker;
    private String name;
    private BigDecimal quantity;
    private BigDecimal averagePurchasePrice;
    /** Read-side FIFO totals, rebuilt from transactions rather than stored in legacy documents. */
    @Transient
    private BigDecimal openCostBasis;
    @Transient
    private BigDecimal commissions;
    @Transient
    private Map<String, BigDecimal> costBasisByCurrency;
    @Transient
    private Map<String, BigDecimal> commissionsByCurrency;
    private BigDecimal priceNow; // for non stock assets to have current price
    private String currency;   // native currency of the asset, e.g. "USD", "EUR"
    private String dbAssetUrl; // TODO webui in portfolio(HoldingList) add click on element that opens profile and non stock asset could have url to some asset db website
    private LocalDate createdAt;
    private LocalDate updatedAt;

    public Holdings(String holdingId, BigDecimal quantity, BigDecimal averagePurchasePrice, LocalDate updatedAt) {
        this.holdingId = holdingId;
        this.quantity = quantity;
        this.averagePurchasePrice = averagePurchasePrice;
        this.updatedAt = updatedAt;
    }
}
