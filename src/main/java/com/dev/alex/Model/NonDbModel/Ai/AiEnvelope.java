package com.dev.alex.Model.NonDbModel.Ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Wrapper every /api/v1/ai/** response ships in.
 * <p>
 * The API answers in each asset's own native currency and never converts (see
 * the currency rule in CLAUDE.md). An AI consumer therefore needs the rates and
 * the formula alongside the numbers, which is what this envelope carries.
 * {@code fxRates} always contains synthetic {@code GBp}/{@code GBx} entries
 * (= GBP x 100) so the pence rule never has to be known by the caller.
 *
 * @param data              the endpoint's payload
 * @param fxRates           currency code -> units per 1 EUR (rateVsEur)
 * @param baseCurrency      what the user reads figures in; a hint, never applied
 * @param conversionFormula literal formula the caller should apply
 * @param notes             caveats that apply to this payload (scaling, nulls, staleness)
 */
public record AiEnvelope<T>(
        T data,
        Map<String, BigDecimal> fxRates,
        String baseCurrency,
        String conversionFormula,
        List<String> notes) {
}
