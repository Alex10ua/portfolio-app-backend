package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class HoldingsCompleteData {

    private String name;
    private String ticker;
    private Double shareAmount;
    private Double costPerShare;
    private Double costBasis;
    private Double currentTotalValue;
    private Double currentShareValue;
    private Double dividend;
    private Double dividendYield;
    private Double dividendYieldOnCost;
    private Double totalProfit;
    private Double totalReceivedDividend;
}
