package com.dev.alex.Model.NonDbModel.Ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Expected dividend income by calendar month, based on the current holding
 * sizes and each ticker's payment schedule.
 * <p>
 * Adds two things the SPA-facing DividendsCalendarData lacks: the currency each
 * amount is quoted in (that DTO has no currency field at all, which is why the
 * UI hardcodes a dollar sign), and the pre-multiplied per-position total.
 */
public record AiDividendCalendar(
        List<Month> months,
        /** currency -&gt; sum over all twelve months */
        Map<String, BigDecimal> annualTotalsByCurrency) {

    public record Month(
            /** "JANUARY" ... "DECEMBER" */
            String month,
            /** 1-12, so callers need not parse the name. */
            int monthNumber,
            List<Entry> entries,
            Map<String, BigDecimal> totalsByCurrency) {
    }

    public record Entry(
            String ticker,
            BigDecimal dividendPerShare,
            BigDecimal shares,
            /** dividendPerShare x shares, in {@code currency}. */
            BigDecimal totalAmount,
            /** Provider quote currency; may be "GBp" (pence). */
            String currency) {
    }
}
