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

}
