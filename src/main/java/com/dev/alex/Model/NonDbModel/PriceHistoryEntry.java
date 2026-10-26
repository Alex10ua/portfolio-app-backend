package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PriceHistoryEntry {
    private LocalDate date;
    private BigDecimal price;
    /** Yahoo Close: split-adjusted, but without the dividend adjustment in price. */
    private BigDecimal rawPrice;

    public PriceHistoryEntry(LocalDate date, BigDecimal price) {
        this(date, price, null);
    }
}
