package com.dev.alex.Model;

import com.dev.alex.Model.NonDbModel.PriceHistoryEntry;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.List;

@Document("priceHistoryCache")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class PriceHistoryCache {
    @Id
    private String ticker;
    private List<PriceHistoryEntry> history;
    private LocalDate lastUpdated;
}
