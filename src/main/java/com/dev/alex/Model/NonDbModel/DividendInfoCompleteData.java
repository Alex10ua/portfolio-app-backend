package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DividendInfoCompleteData {

    List<Map<String, BigDecimal>> tickerAmount;
    // where string ticker bigdecimal amount
    Map<String, BigDecimal> amountByMonth;
    BigDecimal yearlyCombineDividendsProjection;
}
