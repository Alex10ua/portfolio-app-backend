package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Add-ticker search hit: a ticker already known to marketData. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TickerSuggestion {
    private String ticker;
    private String name;
    private String sector;
    private String currency;
    private BigDecimal price;
    private BigDecimal forwardYield;
    /** Already on this portfolio's watchlist. */
    private boolean watched;
}
