package com.dev.alex.Model;

import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("transactions")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class Transactions {
    @Id
    private String portfolioId;
    private String transactionId;
    //private String userId;
    private Date date;
    private Assets assetType;
    private String assetName;
    private String tickerSymbol;
    private TransactionType transactionType;
    private Double quantity;
    private Double price;
    private Double totalAmount;
    private Double commission;
}
