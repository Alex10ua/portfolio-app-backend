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

    // Every amount below is in its own NATIVE currency — no FX is applied here.
    // The client converts to the portfolio's base currency with the rates it holds.

    /** [{ticker: amount}] in the ticker's native (MarketData) currency */
    List<Map<String, BigDecimal>> tickerAmount;
    /** ticker → the currency its dividends are quoted in (e.g. "GBp") */
    Map<String, String> tickerCurrency;
    /** month → native amounts summed across currencies; only exact for a mono-currency portfolio */
    Map<String, BigDecimal> amountByMonth;
    /** currency → (month → amount in that currency) — the convertible breakdown */
    Map<String, Map<String, BigDecimal>> amountByMonthByCurrency;
    /** native projections summed across currencies; only exact for a mono-currency portfolio */
    BigDecimal yearlyCombineDividendsProjection;
    /** currency → next-12-months projection in that currency */
    Map<String, BigDecimal> projectionByCurrency;
    Map<String, BigDecimal> fxRates; // current FX rates (currency → rateVsEur) for frontend conversion
    /** single native currency when the payers all share one, else "USD" — a hint, not applied */
    String displayCurrency;
}
