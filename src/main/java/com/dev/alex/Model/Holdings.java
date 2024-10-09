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
    String portfolioId;
    Assets assetType;
    String tickerSymbol;
    Double quantity;
    Double averagePurchasePrice;
    Date createdAt;
    Date updatedAt;

}
