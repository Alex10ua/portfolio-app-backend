package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * Read-only view of the monthly series in `priceHistoryCache`, kept separate from
 * {@link PriceHistoryCache} because the two series are stored with different date
 * shapes: daily entries are full 'YYYY-MM-DD' dates (LocalDate), monthly ones are
 * 'YYYY-MM' month keys, which no LocalDate converter accepts. Declaring the month
 * as a String is what makes the monthly series readable from Java at all.
 *
 * Undeclared fields of the document (the daily `history`, `lastUpdated`) are simply
 * not mapped.
 */
@Document(collection = "priceHistoryCache")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class MonthlyPriceHistory {
    @Id
    private String ticker;
    private List<MonthlyPricePoint> monthlyHistory;

    /**
     * Prices are {@code Double}, not {@code BigDecimal}, on purpose: a provider can
     * write a NaN close (Yahoo returns a NaN bar for a session with no close yet),
     * and BigDecimal has no NaN — the mapping of the whole document fails, taking
     * every consumer of that ticker's history down with it. Doubles read, and the
     * caller drops the non-finite points. Convert with BigDecimal.valueOf after the
     * finite check.
     */
    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public static class MonthlyPricePoint {
        /** 'YYYY-MM' — the calendar month, not a day. */
        private String date;
        /** Total-return close: back-adjusted for dividends and splits. */
        private Double price;
        /**
         * Close as quoted (splits adjusted, dividends not). Null on documents
         * written before the field existed — callers fall back to {@code price}.
         */
        private Double rawPrice;
    }
}
