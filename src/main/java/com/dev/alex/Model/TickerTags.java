package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.List;

@Document(collection = "tickerTags")
@CompoundIndex(def = "{'username': 1, 'ticker': 1}", unique = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
public class TickerTags {
    @Id
    private String id;
    private String username;
    private String ticker;
    private List<String> tags;
    private LocalDate updatedAt;
}
