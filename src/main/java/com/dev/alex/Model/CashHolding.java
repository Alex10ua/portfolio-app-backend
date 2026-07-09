package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;

@Document(collection = "cashHoldings")
@CompoundIndex(def = "{'portfolioId': 1, 'currency': 1}", unique = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
public class CashHolding {
    @Id
    private String id;
    private String portfolioId;
    private String currency;
    private BigDecimal amount;
    private LocalDate updatedAt;
}
