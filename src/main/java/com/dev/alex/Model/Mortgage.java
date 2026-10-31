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
    }
}
