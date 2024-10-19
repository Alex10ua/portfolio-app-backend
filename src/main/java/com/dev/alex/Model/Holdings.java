package com.dev.alex.Model;

import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.dev.alex.Model.Enums.Assets;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("holdings")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class Holdings {
    @Id
    private String holdingId;
    private String portfolioId;
    private Assets assetType;
    private String name;
    private String tickerSymbol;
    private Double quantity;
    private Double averagePurchasePrice;
    private Date createdAt;
    private Date updatedAt;

    public Holdings(String holdingId, Double quantity, Double averagePurchasePrice, Date updatedAt) {
        this.holdingId = holdingId;
        this.quantity = quantity;
        this.averagePurchasePrice = averagePurchasePrice;
        this.updatedAt = updatedAt;
    }
}
