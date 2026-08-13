package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A ticker watched for a target dividend yield — normally one the portfolio does
 * NOT hold yet. Only the user's own inputs live here (which ticker, at what yield
 * they would buy); price, dividend and yield history are read from marketData /
 * priceHistoryCache at query time, so nothing here goes stale.
 */
@Document(collection = "watchlist")
@CompoundIndex(def = "{'portfolioId': 1, 'ticker': 1}", unique = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
public class WatchlistItem {
    @Id
    private String id;
    private String portfolioId;
    private String ticker;
    /** Forward yield in percent at which the ticker is flagged a buy, e.g. 6.20. */
    private BigDecimal targetYield;
    private LocalDate addedAt;
}
