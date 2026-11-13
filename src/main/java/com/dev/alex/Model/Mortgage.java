package com.dev.alex.Model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Document("mortgages")
// A property may hold several active loans (a mortgage plus a down-payment loan). The old
// one-active-loan unique index is dropped on startup by RealEstateIndexMigration.
@CompoundIndexes({
        @CompoundIndex(name = "mortgages_portfolio_property", def = "{'portfolioId':1,'propertyId':1}")
})
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Mortgage {
    @Id private String id;
    private String portfolioId;
    private String propertyId;
    private String name;
    private String lender;
    private String currency;
    private BigDecimal originalPrincipal;
    private LocalDate loanStartDate;
    private LocalDate firstPaymentDate;
    private LocalDate maturityDate;
    private Integer paymentDay;
    private BigDecimal debtAllocationShare;
    private String balanceTrackingMode;
    private String status;
    private LocalDate closeDate;
    private List<RatePeriod> ratePeriods = new ArrayList<>();
    private List<BalanceCheckpoint> balanceCheckpoints = new ArrayList<>();
    private List<Payment> payments = new ArrayList<>();
    /**
     * Promotions off the agreed rate, such as 0.3 points off for the first 12 installments. The
     * calculation applies them on top of ratePeriods, which keep the contract rate.
     */
    private List<RateDiscount> rateDiscounts = new ArrayList<>();
    /** Null for an annuity loan. Set for a building savings loan, which works differently until its switch. */
    private BuildingSavings buildingSavings;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private Long version;
    @JsonIgnore private String creationFingerprint;
    /** The setup command without its version fields: recognises a retry after a reload lost its key. */
    @JsonIgnore private String setupFingerprint;
    @JsonIgnore private List<RealEstateProperty.RetryReceipt> retryReceipts = new ArrayList<>();

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class RatePeriod {
        private String id;
        private LocalDate effectiveFrom;
        private LocalDate fixedUntil;
        private BigDecimal annualNominalRatePercent;
        private BigDecimal monthlyPayment;
        private String paymentSource;
        private LocalDate anchorDate;
        private BigDecimal calculationPrincipal;
        private Integer remainingPaymentCount;
        private String source;
        private Instant recordedAt;
    }

    /**
     * A promotion: the rate in force is lowered by discountPercent points for installmentCount
     * installments, the first one due on firstDueDate. It covers those installments' whole monthly
     * cycles, so it never splits one. monthlyPayment: the bank's installment meanwhile; null = worked
     * out again at the lower rate over the installments left. Either way the end date holds, and after
     * the promotion a calculated installment is worked out again at the full rate.
     */
    @Data @AllArgsConstructor @NoArgsConstructor
    public static class RateDiscount {
        private String id;
        private LocalDate firstDueDate;
        private Integer installmentCount;
        private BigDecimal discountPercent;
        private BigDecimal monthlyPayment;
        private String note;
        private Instant recordedAt;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class BalanceCheckpoint {
        private String id;
        private LocalDate asOfDate;
        private BigDecimal principalBalance;
        private String source;
        private String note;
        private Instant recordedAt;
    }

    @Data @AllArgsConstructor @NoArgsConstructor
    public static class Payment {
        private String id;
        private LocalDate paymentDate;
        private LocalDate scheduledDueDate;
        private BigDecimal principalPaid;
        private BigDecimal extraPrincipalPaid;
        private BigDecimal interestPaid;
        private BigDecimal feesPaid;
        private String note;
        private Instant recordedAt;
        /** Extra principal that lowers the installment and keeps the end date, instead of ending the loan sooner. */
        private Boolean lowerInstallment;
        /** The bank's installment after that payment; null = the annuity of what is left over the installments left. */
        private BigDecimal newInstallment;
        /** Paid into the building savings account (before the switch only); null = nothing. */
        private BigDecimal savingsPaid;
    }

    /**
     * A building savings loan such as PSS môjÚVER. Until the target amount is allocated it is a
     * bridging loan (medziúver): interest only, while a monthly deposit builds up a savings account.
     * On the switch the savings repay part of the bridging loan and the rest becomes a building loan
     * (stavebný úver), repaid in installments at a rate fixed to the end.
     */
    @Data @AllArgsConstructor @NoArgsConstructor
    public static class BuildingSavings {
        private BigDecimal monthlyDeposit;
        /** Credited monthly on the savings balance; null = none. */
        private BigDecimal savingsRatePercent;
        /** The expected allocation of the target amount; the switch happens on the first installment date from it. */
        private LocalDate allocationDate;
        private BigDecimal buildingLoanRatePercent;
        /** The bank's building-loan installment; null = the annuity of what is left over the installments left. */
        private BigDecimal buildingLoanPayment;
        private List<SavingsCheckpoint> savingsCheckpoints = new ArrayList<>();
    }

    /** The savings account balance from a statement, end of day. */
    @Data @AllArgsConstructor @NoArgsConstructor
    public static class SavingsCheckpoint {
        private String id;
        private LocalDate asOfDate;
        private BigDecimal balance;
        private String source;
        private String note;
        private Instant recordedAt;
    }
}
