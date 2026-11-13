package com.dev.alex.Service.Ai;

import com.dev.alex.Model.Mortgage;
import com.dev.alex.Model.RealEstateProperty;
import com.dev.alex.Model.NonDbModel.Ai.AiRealEstateData.*;
import com.dev.alex.Model.NonDbModel.RealEstateCommands.ExtraPaymentCommand;
import com.dev.alex.Model.NonDbModel.RealEstateCommands.PreviewCommand;
import com.dev.alex.Model.NonDbModel.WealthData;
import com.dev.alex.Model.UserSettings;
import com.dev.alex.Repository.MortgageRepository;
import com.dev.alex.Repository.RealEstatePropertyRepository;
import com.dev.alex.Service.HoldingServiceImpl;
import com.dev.alex.Service.MortgageCalculationService;
import com.dev.alex.Service.RealEstateServiceImpl;
import com.dev.alex.Service.RealEstateServiceImpl.PropertyView;
import com.dev.alex.Service.WealthServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reuses the bank-balance replay rather than teaching an AI caller a second mortgage calculator. */
@Service
public class AiRealEstateAssembler {
    public static final String NOTE_REAL_ESTATE =
            "Property ownershipShare and loan debtAllocationShare are independent fractions. Property equity "
                    + "already subtracts every linked loan's allocated net debt; do not subtract the loans again. "
                    + "netDebt is principal less bound building savings. Unknown financing or balance is not zero. "
                    + "Preserve balance provenance, asOfDate, lastConfirmedDate and all warnings. Compact loan "
                    + "lists omit lifetime payoff projections; get loan detail for those projections.";
    public static final String NOTE_WEALTH =
            "Wealth includes every currently active property regardless of the UI hideRealEstate setting; "
                    + "realEstateHiddenInUi reports that preference without hiding records. Total wealth is "
                    + "investment value plus manual cash plus property equity, after currency conversion. "
                    + "Do not also add owned property value or subtract mortgage principal. This is a wealth "
                    + "valuation, not investment performance, and complete=false means some values are missing.";
    public static final String NOTE_HISTORY =
            "Wealth history excludes cash because historical cash balances are not recorded. Series points "
                    + "are month ends and today. Estimated property/price IDs and missing property IDs describe "
                    + "uncertainty; paused or unknown historical debt must not be treated as zero.";
    public static final String NOTE_PREVIEW =
            "This is an unsaved hypothetical calculation. No loan, rate or payment was recorded. "
                    + "Preserve rate assumptions and warnings; a projected result is not a bank promise.";

    @Autowired private RealEstateServiceImpl realEstate;
    @Autowired private MortgageCalculationService calculations;
    @Autowired private WealthServiceImpl wealth;
    @Autowired private RealEstatePropertyRepository propertyRepository;
    @Autowired private MortgageRepository mortgageRepository;
    @Autowired private AiEnvelopeService envelopeService;
    @Autowired private HoldingServiceImpl holdingService;
    Clock clock = Clock.systemDefaultZone();

    public String baseCurrency(String username, String portfolioId) {
        List<String> currencies = holdingService.getAllHoldingsByPortfolioId(portfolioId).stream()
                .map(h -> h.getCurrency()).filter(c -> c != null && !c.isBlank()).toList();
        // Keep the investment pages' display currency; property-only portfolios still need a useful fallback.
        if (currencies.isEmpty()) {
            currencies = propertyRepository.findAllByPortfolioId(portfolioId).stream()
                    .filter(p -> "ACTIVE".equals(p.getStatus())).map(RealEstateProperty::getCurrency).toList();
        }
        return envelopeService.baseCurrency(username, portfolioId, currencies);
    }

    public List<PropertySummary> properties(String portfolioId, String status) {
        String wanted = status(status, Set.of("ALL", "ACTIVE", "ARCHIVED"));
        LocalDate asOf = today();
        List<Mortgage> loans = mortgageRepository.findAllByPortfolioId(portfolioId);
        return propertyRepository.findAllByPortfolioId(portfolioId).stream()
                .filter(p -> "ALL".equals(wanted) || wanted.equals(p.getStatus()))
                .sorted(Comparator.comparing(RealEstateProperty::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(p -> propertySummary(realEstate.viewFor(p, loans, asOf, false), asOf)).toList();
    }

    public PropertyDetail property(String portfolioId, String propertyId) {
        PropertyView view = realEstate.getProperty(portfolioId, propertyId);
        LocalDate asOf = today();
        List<LoanDetail> loans = view.mortgages().stream()
                .map(loan -> loanDetail(view.property(), loan, fullSummary(view, loan, asOf), asOf)).toList();
        return new PropertyDetail(asOf, view.property(), view.latestValuation(), view.fullValue(), view.ownedValue(),
                view.attributedMortgagePrincipal(), view.attributedBuildingSavings(), view.equity(), view.complete(),
                view.debt(), loans);
    }

    public List<LoanSummary> loans(String portfolioId, String propertyId, String status) {
        String wanted = status(status, Set.of("ALL", "ACTIVE", "CLOSED"));
        LocalDate asOf = today();
        List<RealEstateProperty> properties = propertyId == null || propertyId.isBlank()
                ? propertyRepository.findAllByPortfolioId(portfolioId)
                : List.of(realEstate.requireProperty(portfolioId, propertyId));
        List<Mortgage> loans = mortgageRepository.findAllByPortfolioId(portfolioId);
        return properties.stream().sorted(Comparator.comparing(RealEstateProperty::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .flatMap(property -> loans.stream()
                        .filter(loan -> property.getId().equals(loan.getPropertyId())
                                && portfolioId.equals(loan.getPortfolioId()))
                        .filter(loan -> "ALL".equals(wanted) || wanted.equals(loan.getStatus()))
                        .sorted(Comparator.comparing(Mortgage::getLoanStartDate,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(loan -> loanSummary(property, loan, calculations.summarize(loan, asOf, false), asOf)))
                .toList();
    }

    public LoanDetail loan(String portfolioId, String propertyId, String loanId) {
        RealEstateProperty property = realEstate.requireProperty(portfolioId, propertyId);
        var view = realEstate.getMortgage(portfolioId, propertyId, loanId);
        return loanDetail(property, view.mortgage(), view.summary(), today());
    }

    public SchedulePage schedule(String portfolioId, String propertyId, String loanId,
                                 LocalDate from, LocalDate to, int offset, int limit) {
        Mortgage loan = realEstate.requireMortgage(portfolioId, propertyId, loanId);
        List<MortgageCalculationService.Installment> rows = realEstate.schedule(portfolioId, propertyId, loanId, from, to);
        int safeOffset = Math.max(0, offset);
        int safeLimit = limit <= 0 ? 200 : Math.min(limit, 600);
        int start = Math.min(safeOffset, rows.size());
        int end = start + Math.min(safeLimit, rows.size() - start);
        return new SchedulePage(propertyId, loanId, loan.getCurrency(), rows.size(), safeOffset, safeLimit,
                end < rows.size(), List.copyOf(rows.subList(start, end)));
    }

    public WealthSnapshot wealth(String portfolioId, String username) {
        WealthData.Summary current = wealth.current(portfolioId);
        boolean hidden = envelopeService.portfolioSettings(username, portfolioId)
                .map(UserSettings.PortfolioSettings::getHideRealEstate).orElse(false);
        return new WealthSnapshot(current.asOfDate(), current.investmentValueByCurrency(),
                current.ownedPropertyValueByCurrency(), current.mortgagePrincipalByCurrency(),
                current.propertyEquityByCurrency(), current.manualCashByCurrency(), current.properties().stream()
                .map(view -> propertySummary(view, current.asOfDate())).toList(), current.investmentCount(),
                current.complete(), current.missingPropertyIds(), current.missingInvestmentTickers(), hidden,
                "ALL_ACTIVE_PROPERTIES_REGARDLESS_OF_UI_VISIBILITY");
    }

    public List<WealthData.HistoryPoint> wealthHistory(String portfolioId, LocalDate from, LocalDate to,
                                                      Integer maxPoints) {
        List<WealthData.HistoryPoint> points = wealth.history(portfolioId, from, to);
        int max = maxPoints == null || maxPoints <= 0 ? 120 : Math.min(maxPoints, 600);
        if (points.size() <= max) return points;
        if (max == 1) return List.of(points.getLast());
        List<WealthData.HistoryPoint> sampled = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            sampled.add(points.get((int) ((long) i * (points.size() - 1) / (max - 1))));
        }
        return List.copyOf(sampled);
    }

    public ExtraPaymentPreview extraPaymentPreview(String portfolioId, String propertyId, String loanId,
                                                   LocalDate date, BigDecimal amount) {
        Mortgage loan = realEstate.requireMortgage(portfolioId, propertyId, loanId);
        return new ExtraPaymentPreview(propertyId, loanId, loan.getCurrency(), false,
                realEstate.extraPaymentPreview(portfolioId, propertyId, loanId, new ExtraPaymentCommand(date, amount)));
    }

    public RatePreview ratePreview(String portfolioId, String propertyId, String loanId, LocalDate effectiveFrom,
                                   LocalDate fixedUntil, BigDecimal annualNominalRatePercent,
                                   BigDecimal monthlyPayment, BigDecimal extraPrincipalPaid) {
        if (annualNominalRatePercent == null) throw new IllegalArgumentException("annualNominalRatePercent is required");
        if (extraPrincipalPaid != null && extraPrincipalPaid.signum() < 0) {
            throw new IllegalArgumentException("extraPrincipalPaid must not be negative");
        }
        Mortgage loan = realEstate.requireMortgage(portfolioId, propertyId, loanId);
        return new RatePreview(propertyId, loanId, loan.getCurrency(), false,
                realEstate.preview(portfolioId, propertyId, loanId, new PreviewCommand(effectiveFrom, fixedUntil,
                        annualNominalRatePercent, monthlyPayment, extraPrincipalPaid)));
    }

    private PropertySummary propertySummary(PropertyView view, LocalDate asOf) {
        RealEstateProperty property = view.property();
        List<LoanSummary> loans = view.mortgages().stream().map(loan -> {
            var summary = view.loanSummaries() == null ? null : view.loanSummaries().get(loan.getId());
            if (summary == null) summary = calculations.summarize(loan, asOf, false);
            return loanSummary(property, loan, summary, asOf);
        }).toList();
        return new PropertySummary(asOf, property.getId(), property.getPortfolioId(), property.getName(),
                property.getPropertyType(), property.getUsage(), property.getCurrency(), property.getStatus(),
                property.getFinancingStatus(), property.getOwnershipShare(), property.getTrackingStartDate(),
                property.getArchiveDate(), view.latestValuation(), view.fullValue(), view.ownedValue(),
                view.attributedMortgagePrincipal(), view.attributedBuildingSavings(), view.equity(), view.complete(),
                view.debt(), loans);
    }

    private MortgageCalculationService.Summary fullSummary(PropertyView view, Mortgage loan, LocalDate asOf) {
        var summary = view.loanSummaries() == null ? null : view.loanSummaries().get(loan.getId());
        return summary == null ? calculations.summarize(loan, asOf) : summary;
    }

    private static LoanSummary loanSummary(RealEstateProperty property, Mortgage loan,
                                           MortgageCalculationService.Summary summary, LocalDate asOf) {
        BigDecimal debt = netDebt(summary);
        return new LoanSummary(asOf, loan.getId(), loan.getPortfolioId(), property.getId(), property.getName(),
                loan.getName(), loan.getLender(), loan.getCurrency(), loan.getStatus(), loanType(loan),
                loan.getBalanceTrackingMode(), loan.getOriginalPrincipal(), loan.getDebtAllocationShare(),
                loan.getLoanStartDate(), loan.getMaturityDate(), loan.getCloseDate(), debt, attributedDebt(loan, debt),
                false, summary);
    }

    private static LoanDetail loanDetail(RealEstateProperty property, Mortgage loan,
                                         MortgageCalculationService.Summary summary, LocalDate asOf) {
        BigDecimal debt = netDebt(summary);
        return new LoanDetail(asOf, property.getId(), property.getName(), loan.getCurrency(), loanType(loan),
                loan, summary, debt, attributedDebt(loan, debt));
    }

    private static BigDecimal netDebt(MortgageCalculationService.Summary summary) {
        return summary == null || summary.balance() == null ? null : summary.balance().netDebt();
    }

    private static BigDecimal attributedDebt(Mortgage loan, BigDecimal debt) {
        BigDecimal allocation = loan.getDebtAllocationShare() == null ? BigDecimal.ONE : loan.getDebtAllocationShare();
        if (allocation.signum() == 0) return BigDecimal.ZERO;
        return debt == null ? null : debt.multiply(allocation);
    }

    private static String loanType(Mortgage loan) {
        return loan.getBuildingSavings() == null ? "ANNUITY" : "BUILDING_SAVINGS";
    }

    private static String status(String value, Set<String> allowed) {
        String result = value == null || value.isBlank() ? "ALL" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(result)) throw new IllegalArgumentException("Unknown status: " + value
                + ". Expected one of " + String.join(", ", allowed) + ".");
        return result;
    }

    private LocalDate today() { return LocalDate.now(clock); }
}
