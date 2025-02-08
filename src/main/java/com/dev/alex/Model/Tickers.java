package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Document("tickers")
public class Tickers {

    @Id
    private String id;
    private String ticker;


    public Tickers(String ticker) {
        this.ticker = ticker;
    }
}
