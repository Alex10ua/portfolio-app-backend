package com.dev.alex.Model;

import java.util.Date;

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
    private String tickerSymbol;
    private Date date;
    private Double price;
    private Double forwardDividend;
    private Date nextDividenPayout;
}
