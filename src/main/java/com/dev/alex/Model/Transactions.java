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
    String portfolioId;
    String transactionId;
    Date date;
    Assets assetType;
    String tickerSymbol;
    TransactionType transactionType;
    Double quantity;
    Double price;
    Double totalAmount;
}
