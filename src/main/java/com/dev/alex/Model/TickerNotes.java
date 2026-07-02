package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

@Document(collection = "tickerNotes")
@CompoundIndex(def = "{'username': 1, 'portfolioId': 1, 'ticker': 1}", unique = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
public class TickerNotes {
    @Id
    private String id;
    private String username;
    private String portfolioId;
    private String ticker;
    private String note;
    private LocalDate updatedAt;
}
