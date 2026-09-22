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
    private String assetType;
    private BigDecimal shareAmount;
    /**
     * Unrounded holding quantity. {@code shareAmount} is rounded to 2dp for display, which is the
     * wrong number to sell: closing a 5.994666 position with the rounded 5.99 leaves dust behind
     * and the holding never disappears. Any client booking a quantity must use this field.
     */
    private BigDecimal exactShareAmount;
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
    private String currency;
    private BigDecimal fxRate;
    private Long sharesOutstanding; // total shares outstanding (STOCK only); used by Ownership view
}
