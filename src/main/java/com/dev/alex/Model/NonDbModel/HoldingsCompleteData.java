package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class HoldingsCompleteData {

    private String name;
    private String ticker;
    private BigDecimal shareAmount;
    private BigDecimal costPerShare;
    private BigDecimal costBasis;
    private BigDecimal currentTotalValue;
    private BigDecimal currentShareValue;
    private BigDecimal dividend;
    private BigDecimal dividendYield;
    private BigDecimal dividendYieldOnCost;
    private BigDecimal totalReceivedDividend;
    private BigDecimal totalProfit;
    private BigDecimal totalProfitPercentage;
    private BigDecimal dailyChange;
}
