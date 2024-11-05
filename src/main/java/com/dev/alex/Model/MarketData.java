package com.dev.alex.Model;

import java.util.Date;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("marketData")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class MarketData {
    @Id
    private String ticker;
    private String name;
    private Double price;
    private List<Dividend> dividends;
    private List<Splits> splits;
    private Date updatedAt;

    public MarketData(Double price, Date updatedAt, String ticker) {
        this.price = price;
        this.updatedAt = updatedAt;
        this.ticker = ticker;
    }
}
