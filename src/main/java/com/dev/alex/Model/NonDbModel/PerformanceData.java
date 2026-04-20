package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PerformanceData {
    private BigDecimal totalInvested;
    private BigDecimal currentValue;
    private BigDecimal unrealizedPnL;
    private BigDecimal unrealizedPnLPct;
    private BigDecimal realizedPnL;
    private BigDecimal totalDividends;
    private BigDecimal totalReturn;
    private BigDecimal totalReturnPct;
    private BigDecimal xirr;
    private List<PerformancePoint> timeSeries;
}
