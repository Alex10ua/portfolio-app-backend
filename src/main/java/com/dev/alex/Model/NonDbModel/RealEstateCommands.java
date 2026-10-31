package com.dev.alex.Model.NonDbModel;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Command-only shapes keep persisted identity, audit data and retry metadata off the write surface. */
public final class RealEstateCommands {
    private RealEstateCommands() {}

    public record PropertyCommand(Long version, String name, String propertyType, String usage, String currency,
            String country, String location, LocalDate purchaseDate, BigDecimal purchasePrice,
            BigDecimal acquisitionCosts, BigDecimal ownershipShare, LocalDate trackingStartDate,
            String financingStatus, String notes, LocalDate valuationDate, BigDecimal fullPropertyValue,
            String valuationSource) {}

    public record ValuationCommand(Long version, LocalDate effectiveDate, BigDecimal fullPropertyValue,
                                   String source, String note) {}

    public record MortgageCommand(Long version, Long propertyVersion, String name, String lender, String currency,
            BigDecimal originalPrincipal, LocalDate loanStartDate, LocalDate firstPaymentDate, LocalDate maturityDate,
            Integer paymentDay, BigDecimal debtAllocationShare, String balanceTrackingMode,
            BalanceCommand openingBalance, RateCommand ratePeriod) {}

    public record PaymentCommand(Long version, LocalDate paymentDate, LocalDate scheduledDueDate,
            BigDecimal principalPaid, BigDecimal extraPrincipalPaid, BigDecimal interestPaid,
            BigDecimal feesPaid, String note, Boolean lowerInstallment, BigDecimal newInstallment) {}

    /** An unsaved "what if I pay this much extra on this day". */
    public record ExtraPaymentCommand(LocalDate date, BigDecimal amount) {}

    public record BalanceCommand(Long version, LocalDate asOfDate, BigDecimal principalBalance,
                                 String source, String note) {}

    public record RateCommand(Long version, LocalDate effectiveFrom, LocalDate fixedUntil,
            BigDecimal annualNominalRatePercent, BigDecimal monthlyPayment, String paymentSource) {}

    public record DateCommand(Long version, LocalDate date) {}

    public record PreviewCommand(LocalDate effectiveFrom, LocalDate fixedUntil, BigDecimal annualNominalRatePercent,
                                 BigDecimal monthlyPayment, BigDecimal extraPrincipalPaid) {}
}
