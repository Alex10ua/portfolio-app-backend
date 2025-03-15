package com.dev.alex.Model;

import java.math.BigDecimal;
import java.time.LocalDate;
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

/**
 * This class is a model for transactions in the database
 * this class can be used to store transaction for other assets like:
 * @see Assets
 *
 * @author olazoriak
 * @version 1.0
 * @since 1.0
 */
public class Transactions {
    @Id
    private String transactionId;
    private String portfolioId;
    private LocalDate date;
    private Assets assetType;
    private String ticker; // for stock assets
    private String name; // for non stock assets
    private TransactionType transactionType;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal amount;//for dividends transaction
    private BigDecimal priceNow;//for non-stock assets to have current price
    private BigDecimal totalAmount;
    private String currency;
    private BigDecimal commission;
}
