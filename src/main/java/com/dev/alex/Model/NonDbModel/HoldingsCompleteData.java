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
    /** Book currency: the one the position was bought in; costPerShare and costBasis are in it. */
    private String currency;
    /**
     * Currency the provider quotes this ticker in (MarketData.currency, may be "GBp" pence):
     * currentShareValue, currentTotalValue, dailyChange and dividend are in it, not in
     * {@code currency}. Null when the provider never reported one — the market figures are then
     * taken to be in {@code currency}. When the two differ, totalProfit, totalProfitPercentage
     * and dividendYieldOnCost are null: each would compare a quote-currency figure with a
     * book-currency one, which takes FX, and FX is the client's job.
     */
    private String quoteCurrency;
    private BigDecimal fxRate;
    private Long sharesOutstanding; // total shares outstanding (STOCK only); used by Ownership view
}
