package com.dev.alex.Model.NonDbModel.Ai;

import com.dev.alex.Model.Mortgage;
import com.dev.alex.Model.RealEstateProperty;
import com.dev.alex.Service.MortgageCalculationService;
import com.dev.alex.Service.RealEstateServiceImpl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** List reads omit stored payment/checkpoint arrays; detail reads retain the source records. */
public final class AiRealEstateData {
    private AiRealEstateData() {}

    public record LoanSummary(LocalDate asOfDate, String loanId, String portfolioId, String propertyId,
                              String propertyName, String name, String lender, String currency,
                              String status, String loanType, String balanceTrackingMode,
                              BigDecimal originalPrincipal, BigDecimal debtAllocationShare,
                              LocalDate loanStartDate, LocalDate maturityDate, LocalDate closeDate,
                              BigDecimal netDebt, BigDecimal attributedNetDebt,
                              boolean projectionIncluded, MortgageCalculationService.Summary summary) {}

    public record LoanDetail(LocalDate asOfDate, String propertyId, String propertyName, String currency,
                             String loanType, Mortgage mortgage, MortgageCalculationService.Summary summary,
                             BigDecimal netDebt, BigDecimal attributedNetDebt) {}

    public record PropertySummary(LocalDate asOfDate, String propertyId, String portfolioId, String name,
                                  String propertyType, String usage, String currency, String status,
                                  String financingStatus, BigDecimal ownershipShare,
                                  LocalDate trackingStartDate, LocalDate archiveDate,
                                  RealEstateProperty.Valuation latestValuation,
                                  BigDecimal fullValue, BigDecimal ownedValue,
                                  BigDecimal attributedMortgagePrincipal, BigDecimal attributedBuildingSavings,
                                  BigDecimal equity, boolean complete, RealEstateServiceImpl.DebtStatus debt,
                                  List<LoanSummary> loans) {}

    public record PropertyDetail(LocalDate asOfDate, RealEstateProperty property,
                                 RealEstateProperty.Valuation latestValuation,
                                 BigDecimal fullValue, BigDecimal ownedValue,
                                 BigDecimal attributedMortgagePrincipal, BigDecimal attributedBuildingSavings,
                                 BigDecimal equity, boolean complete, RealEstateServiceImpl.DebtStatus debt,
                                 List<LoanDetail> loans) {}

    public record SchedulePage(String propertyId, String loanId, String currency, int total,
                               int offset, int limit, boolean hasMore,
                               List<MortgageCalculationService.Installment> items) {}

    public record WealthSnapshot(LocalDate asOfDate, Map<String, BigDecimal> investmentValueByCurrency,
                                 Map<String, BigDecimal> ownedPropertyValueByCurrency,
                                 Map<String, BigDecimal> mortgagePrincipalByCurrency,
                                 Map<String, BigDecimal> propertyEquityByCurrency,
                                 Map<String, BigDecimal> manualCashByCurrency,
                                 List<PropertySummary> properties, int investmentCount,
                                 boolean complete, List<String> missingPropertyIds,
                                 List<String> missingInvestmentTickers, boolean realEstateHiddenInUi,
                                 String inclusionPolicy) {}

    public record ExtraPaymentPreview(String propertyId, String loanId, String currency,
                                      boolean saved, RealEstateServiceImpl.ExtraPaymentPreview preview) {}

    public record RatePreview(String propertyId, String loanId, String currency,
                              boolean saved, RealEstateServiceImpl.Preview preview) {}
}
