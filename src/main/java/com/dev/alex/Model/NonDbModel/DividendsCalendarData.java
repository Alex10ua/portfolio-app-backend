package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DividendsCalendarData {

    String ticker;
    BigDecimal dividendAmount;
    BigDecimal stockQuantity;
    /**
     * Projected from the ticker's past payment pattern rather than a dividend
     * the provider has on its books. Every entry of the rolling projection is
     * one; in a yeared calendar only the months nothing was declared for yet.
     */
    boolean scheduled;

}
