package com.dev.alex.Service;

import com.dev.alex.Model.Mortgage;
import com.dev.alex.Model.RealEstateProperty;
import com.dev.alex.Model.NonDbModel.PerformancePoint;
import com.dev.alex.Model.NonDbModel.WealthData;
import com.dev.alex.Repository.CashHoldingRepository;
import com.dev.alex.Repository.MortgageRepository;
import com.dev.alex.Repository.RealEstatePropertyRepository;
import com.dev.alex.Service.RealEstateServiceImpl.PropertyView;
import com.dev.alex.Service.Interface.WealthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

@Service
public class WealthServiceImpl implements WealthService {
    @Autowired private HoldingsCompleteDataServiceImpl holdings;
    @Autowired private PortfolioPerformanceServiceImpl performance;
    @Autowired private RealEstatePropertyRepository properties;
    @Autowired private MortgageRepository mortgages;
    @Autowired private CashHoldingRepository cash;
    @Autowired private RealEstateServiceImpl realEstate;

    @Override
    public WealthData.Summary current(String portfolioId) {
        LocalDate today = LocalDate.now();
        List<PropertyView> views = views(properties.findAllByPortfolioId(portfolioId),
                mortgages.findAllByPortfolioId(portfolioId), today);
        Components property = components(views);
        Map<String, BigDecimal> investments = new TreeMap<>();
        List<String> missingQuotes = new ArrayList<>();
        var positions = holdings.getAllHoldingsByPortfolioId(portfolioId);
        for (var position : positions) {
            if (position.getCurrentTotalValue() == null) {
                missingQuotes.add(position.getTicker());
            } else {
                add(investments, currency(position.getQuoteCurrency(), position.getCurrency()),
                        position.getCurrentTotalValue());
            }
        }
        Map<String, BigDecimal> manualCash = new TreeMap<>();
        for (var entry : cash.findAllByPortfolioId(portfolioId)) {
            add(manualCash, currency(entry.getCurrency(), null), entry.getAmount());
        }
        return new WealthData.Summary(today, investments, property.owned(), property.debt(),
                property.equity(), manualCash, views, positions.size(),
                property.missing().isEmpty() && missingQuotes.isEmpty(), property.missing(), missingQuotes);
    }

    /** Batch-load each domain once; chart months never trigger another database query. */
    @Override
    public List<WealthData.HistoryPoint> history(String portfolioId, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate end = to == null || to.isAfter(today) ? today : to;
        if (from != null && from.isAfter(end)) throw new IllegalArgumentException("History start must not follow end");
        List<RealEstateProperty> allProperties = properties.findAllByPortfolioId(portfolioId);
        List<Mortgage> allLoans = mortgages.findAllByPortfolioId(portfolioId);
        List<PerformancePoint> investmentPoints = performance.getMonthlyHistory(portfolioId);
        LocalDate earliest = investmentPoints.stream().map(PerformancePoint::getDate)
                .min(LocalDate::compareTo).orElse(end);
        for (var property : allProperties) {
            if (property.getTrackingStartDate() != null && property.getTrackingStartDate().isBefore(earliest))
                earliest = property.getTrackingStartDate();
        }
        LocalDate start = from == null ? earliest : from;
        if (YearMonth.from(start).until(YearMonth.from(end), java.time.temporal.ChronoUnit.MONTHS) > 1200)
            throw new IllegalArgumentException("Choose a history range of at most 100 years");
        TreeMap<LocalDate, PerformancePoint> investmentByDate = new TreeMap<>();
        for (var point : investmentPoints) investmentByDate.put(point.getDate(), point);
        TreeSet<LocalDate> dates = new TreeSet<>();
        YearMonth month = YearMonth.from(start);
        while (!month.isAfter(YearMonth.from(end))) {
            LocalDate date = month.atEndOfMonth();
            if (!date.isBefore(start) && !date.isAfter(end)) dates.add(date);
            month = month.plusMonths(1);
        }
        // A historical custom upper bound is a filter, not a new securities valuation date.
        if (!today.isBefore(start) && !today.isAfter(end)) dates.add(today);
        List<WealthData.HistoryPoint> result = new ArrayList<>();
        for (LocalDate date : dates) {
            // Samples are completed month-ends plus today's live investment valuation.
            var investment = investmentByDate.floorEntry(date);
            Map<String, BigDecimal> investmentValues = investment == null ? Map.of()
                    : investment.getValue().getValueByCurrency();
            if (investmentValues == null) investmentValues = Map.of();
            List<PropertyView> propertyViews = views(allProperties, allLoans, date);
            Components property = components(propertyViews, true);
            List<String> estimated = propertyViews.stream().filter(v -> v.debt() != null
                    && !"CONFIRMED".equals(v.debt().provenance()))
                    .map(v -> v.property().getId()).toList();
            List<String> estimatedPrices = investment == null || investment.getValue().getEstimatedPriceTickers() == null
                    ? List.of() : investment.getValue().getEstimatedPriceTickers();
            result.add(new WealthData.HistoryPoint(date, investmentValues, property.owned(), property.debt(),
                    property.equity(), property.missing().isEmpty(), property.missing(), estimated, estimatedPrices));
        }
        return result;
    }

    @Override
    public List<WealthData.PropertyHistoryPoint> propertyHistory(String portfolioId, String propertyId) {
        RealEstateProperty property = properties.findByIdAndPortfolioId(propertyId, portfolioId)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Property not found"));
        List<Mortgage> loans = mortgages.findAllByPortfolioIdAndPropertyId(portfolioId, propertyId);
        LocalDate today = LocalDate.now();
        LocalDate start = property.getTrackingStartDate();
        TreeSet<LocalDate> dates = new TreeSet<>();
        dates.add(start);
        dates.add(today);
        for (YearMonth month = YearMonth.from(start); !month.isAfter(YearMonth.from(today)); month = month.plusMonths(1))
            dates.add(month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth());
        for (var valuation : property.getValuations()) dates.add(valuation.getEffectiveDate());
        for (var loan : loans) {
            for (var checkpoint : loan.getBalanceCheckpoints()) dates.add(checkpoint.getAsOfDate());
            for (var payment : loan.getPayments()) dates.add(payment.getPaymentDate());
            if (loan.getCloseDate() != null) dates.add(loan.getCloseDate());
        }
        return dates.stream().filter(date -> !date.isBefore(start) && !date.isAfter(today))
                .filter(date -> property.getArchiveDate() == null || !date.isAfter(property.getArchiveDate()))
                .map(date -> {
                    PropertyView view = realEstate.viewFor(property, loans, date, false);
                    var debt = view.debt();
                    boolean historicalDebtKnown = historicalDebtKnown(view);
                    return new WealthData.PropertyHistoryPoint(date, view.fullValue(), view.ownedValue(),
                            historicalDebtKnown ? view.attributedMortgagePrincipal() : null,
                            historicalDebtKnown ? view.equity() : null, view.complete() && historicalDebtKnown,
                            debt == null ? view.complete() ? "CONFIRMED" : "UNKNOWN" : debt.provenance(),
                            debt == null ? List.of() : debt.warnings());
                }).toList();
    }

    private List<PropertyView> views(List<RealEstateProperty> all, List<Mortgage> loans, LocalDate date) {
        Map<String, List<Mortgage>> byProperty = new HashMap<>();
        for (var loan : loans) byProperty.computeIfAbsent(loan.getPropertyId(), key -> new ArrayList<>()).add(loan);
        return all.stream().filter(p -> activeAt(p, date))
                .map(p -> realEstate.viewFor(p, byProperty.getOrDefault(p.getId(), List.of()), date, false)).toList();
    }

    static boolean activeAt(RealEstateProperty property, LocalDate date) {
        return property.getTrackingStartDate() != null && !property.getTrackingStartDate().isAfter(date)
                && (property.getArchiveDate() == null || property.getArchiveDate().isAfter(date));
    }

    private record Components(Map<String, BigDecimal> owned, Map<String, BigDecimal> debt,
                              Map<String, BigDecimal> equity, List<String> missing) {}

    private Components components(List<PropertyView> views) {
        return components(views, false);
    }

    private Components components(List<PropertyView> views, boolean historical) {
        Map<String, BigDecimal> owned = new TreeMap<>(), debt = new TreeMap<>(), equity = new TreeMap<>();
        List<String> missing = new ArrayList<>();
        for (var view : views) {
            String currency = view.property().getCurrency();
            add(owned, currency, view.ownedValue());
            boolean debtKnown = !historical || historicalDebtKnown(view);
            if (debtKnown) add(debt, currency, view.attributedMortgagePrincipal());
            if (!view.complete() || view.equity() == null || !debtKnown) missing.add(view.property().getId());
            else add(equity, currency, view.equity());
        }
        return new Components(owned, debt, equity, missing);
    }

    /**
     * A frozen last-known balance may be displayed today, but is not a tracked historical estimate.
     * The debt status carries the least certain loan, so one paused loan makes the month unknown.
     */
    private static boolean historicalDebtKnown(PropertyView view) {
        return view.debt() == null || !"PAUSED".equals(view.debt().provenance()) && !"UNKNOWN".equals(view.debt().provenance())
                || view.attributedMortgagePrincipal() != null && view.attributedMortgagePrincipal().signum() == 0;
    }

    private static void add(Map<String, BigDecimal> target, String currency, BigDecimal value) {
        if (value != null) target.merge(currency, value, BigDecimal::add);
    }

    private static String currency(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred
                : fallback != null && !fallback.isBlank() ? fallback : "USD";
    }
}
