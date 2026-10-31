package com.dev.alex.Model.NonDbModel;

import com.dev.alex.Service.RealEstateServiceImpl.PropertyView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Native-currency components. Consumers convert before adding different currencies. */
public final class WealthData {
    private WealthData() {}

    public record Summary(LocalDate asOfDate, Map<String, BigDecimal> investmentValueByCurrency,
                          Map<String, BigDecimal> ownedPropertyValueByCurrency,
                          Map<String, BigDecimal> mortgagePrincipalByCurrency,
                          Map<String, BigDecimal> propertyEquityByCurrency,
                          Map<String, BigDecimal> manualCashByCurrency,
                          List<PropertyView> properties, int investmentCount,
                          boolean complete, List<String> missingPropertyIds,
                          List<String> missingInvestmentTickers) {}

    public record HistoryPoint(LocalDate date, Map<String, BigDecimal> investmentValueByCurrency,
                               Map<String, BigDecimal> ownedPropertyValueByCurrency,
                               Map<String, BigDecimal> mortgagePrincipalByCurrency,
                               Map<String, BigDecimal> propertyEquityByCurrency,
                               boolean complete, List<String> missingPropertyIds,
                               List<String> estimatedPropertyIds, List<String> estimatedPriceTickers) {}

    public record PropertyHistoryPoint(LocalDate date, BigDecimal fullValue, BigDecimal ownedValue,
                                       BigDecimal attributedMortgagePrincipal, BigDecimal equity,
                                       boolean complete, String provenance, List<String> warnings) {}
}
