package com.dev.alex.Model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import com.fasterxml.jackson.annotation.JsonIgnore;

import com.dev.alex.Model.Enums.Assets;
import com.dev.alex.Model.Enums.TransactionType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("transactions")
@CompoundIndexes({
        @CompoundIndex(name = "transaction_creation_key", def = "{'portfolioId': 1, 'idempotencyKey': 1}",
                unique = true, partialFilter = "{'idempotencyKey': {'$type': 'string'}}"),
        @CompoundIndex(name = "transactions_portfolio_date", def = "{'portfolioId': 1, 'date': 1}"),
        @CompoundIndex(name = "transactions_portfolio_ticker_date", def = "{'portfolioId': 1, 'ticker': 1, 'date': 1}")
})
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
    @Indexed(name = "transactions_import_batch")
    private String importBatchId;
    // Nullable additive metadata: legacy/imported rows are outside the partial unique index.
    // Kept on edits so a retry is compared with the original request, not today's row.
    @JsonIgnore
    private String idempotencyKey;
    @JsonIgnore
    private String creationRequestHash;
    @JsonIgnore
    private Boolean creationHoldingSynced;
    @Transient
    private BigDecimal fxRate; // populated at query time from exchangeRates, not stored in DB
}
