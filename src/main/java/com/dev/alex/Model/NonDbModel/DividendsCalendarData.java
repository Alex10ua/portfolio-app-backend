package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DividendsCalendarData {

    String ticker;
    BigDecimal dividendAmount;
    BigDecimal stockQuantity;
    /**
     * Projected from the ticker's past payment pattern rather than a dividend
     * the provider has on its books. Every entry of the rolling projection is
     * one; in a yeared calendar only the months nothing was declared for yet.
     */
    boolean scheduled;

    /**
     * Ex-dividend date. Yahoo dates every stored dividend by its ex-date, so for
     * a declared payment this is the stored date; for a scheduled one it is the
     * template payment's date moved into the year it is projected onto.
     */
    LocalDate exDate;
    /**
     * When the cash lands, or null when the provider reports no pay date for
     * this ticker. Exact only for the payment Yahoo's statistics describe (its
     * {@code exDividendDate}/{@code dividendDate} pair); every other payment is
     * its ex-date plus that same lag, which a company keeps stable from one
     * payment to the next.
     */
    LocalDate payDate;
    /** {@code payDate} derived from the lag rather than reported for this payment. */
    boolean payDateEstimated;

    /** From {@code MarketData}: display name, listing country (withholding default) and quote currency. */
    String name;
    String country;
    /** Currency of {@code dividendAmount} — {@code MarketData.currency}, so {@code GBp} for a London line. */
    String currency;
    /**
     * Months (1–12) the ticker pays in: the selected year's for a closed year,
     * the trailing twelve months' otherwise, filled up to {@code paymentsPerYear}
     * from earlier years when a payment is missing from that window.
     */
    List<Integer> paymentMonths;
    /**
     * The ticker's usual payments per year: the most common yearly count of the
     * last five finished years. Null when it has no dividend history to count.
     */
    Integer paymentsPerYear;

    public DividendsCalendarData(String ticker, BigDecimal dividendAmount, BigDecimal stockQuantity,
                                 boolean scheduled) {
        this.ticker = ticker;
        this.dividendAmount = dividendAmount;
        this.stockQuantity = stockQuantity;
        this.scheduled = scheduled;
    }
}
