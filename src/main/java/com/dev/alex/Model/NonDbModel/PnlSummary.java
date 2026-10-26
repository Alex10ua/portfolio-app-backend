package com.dev.alex.Model.NonDbModel;

import java.math.BigDecimal;
import java.util.Map;

/** Native-currency dashboard figures; fees are already included in realized/open FIFO cost. */
public record PnlSummary(Map<String, BigDecimal> realizedPnLByCurrency,
                         Map<String, BigDecimal> commissionsByCurrency,
                         Map<String, BigDecimal> realizedCommissionsByCurrency) {
}
