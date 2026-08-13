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
    /** Daily closes, `date` = 'YYYY-MM-DD'. */
    private List<PriceHistoryEntry> history;
    /**
     * Monthly closes. Deliberately NOT `PriceHistoryEntry`: the monthly `date` is a
     * 'YYYY-MM' month key, which no LocalDate converter accepts — typing it as one
     * made every full load of this document throw, which is exactly what happened
     * to the portfolio-history chart once tickers started getting a monthly series.
     * See {@link MonthlyPriceHistory} for the projection that reads only this list.
     */
    private List<MonthlyPriceHistory.MonthlyPricePoint> monthlyHistory;
    private LocalDate lastUpdated;
}
