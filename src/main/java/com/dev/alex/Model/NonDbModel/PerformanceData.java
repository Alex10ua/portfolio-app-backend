package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Portfolio performance. The scalar fields are the per-currency maps below summed with no FX,
 * so they are exact only when everything shares one currency — and a coin bought in EUR but
 * quoted in USD, or a London line quoted in pence, already breaks that. Clients convert the
 * maps (and the cash flows, for XIRR) with their own rates, the same way they convert the
 * time series.
 */
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
    private BigDecimal totalCommissions;
    private List<PerformancePoint> timeSeries;

    /** BUY cost plus commission, per transaction currency. */
    private Map<String, BigDecimal> totalInvestedByCurrency;
    /** Open positions at the latest price, per quote currency (may be "GBp"). */
    private Map<String, BigDecimal> currentValueByCurrency;
    /** Remaining FIFO purchase cost, including allocated BUY fees, in each lot's book currency. */
    private Map<String, BigDecimal> openCostBasisByCurrency;
    /** FIFO proceeds less consumed cost and fees, retained in their original currencies. */
    private Map<String, BigDecimal> realizedPnLByCurrency;
    /** All BUY/SELL fees, including fees still allocated to open lots. */
    private Map<String, BigDecimal> commissionsByCurrency;
    /** SELL fees and the BUY fees allocated to the sold quantities. */
    private Map<String, BigDecimal> realizedCommissionsByCurrency;
    /** DIVIDEND transactions, per transaction currency. */
    private Map<String, BigDecimal> totalDividendsByCurrency;
    /**
     * Every BUY (negative), SELL and DIVIDEND (positive) as booked, for a client-side XIRR in
     * the display currency. The terminal value is {@link #currentValueByCurrency} as of today.
     */
    private List<CashFlow> cashFlows;

    public record CashFlow(LocalDate date, BigDecimal amount, String currency) {
    }
}
