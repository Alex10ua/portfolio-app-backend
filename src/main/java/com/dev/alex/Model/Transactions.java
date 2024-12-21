package com.dev.alex.Model;

import java.math.BigDecimal;
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
    private String id;
    private String portfolioId;
    private String transactionId;
    private Date date;
    private Assets assetType;
    private String assetName;
    private String ticker;
    private TransactionType transactionType;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal totalAmount;
    private BigDecimal commission;
}
