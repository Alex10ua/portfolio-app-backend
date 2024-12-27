package com.dev.alex.Model;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@AllArgsConstructor
@NoArgsConstructor
@Data
@Document("marketData")
public class MarketData {
    @Id
    private String id;
    private String ticker;
    private String name;
    private BigDecimal price;
    private BigDecimal priceYesterday;
    private BigDecimal lastDividendPayment;
    private BigDecimal yearlyDividend;
    private List<Dividend> dividends;
    private List<Splits> splits;
    private String country;
    private String sector;
    private Date updatedAt;

    public MarketData(BigDecimal price, Date updatedAt, String ticker) {
        this.price = price;
        this.updatedAt = updatedAt;
        this.ticker = ticker;
    }
}
