package com.dev.alex;

import com.dev.alex.Model.CashHolding;
import com.dev.alex.Model.Mortgage;
import com.dev.alex.Model.RealEstateProperty;
import com.dev.alex.Model.NonDbModel.HoldingsCompleteData;
import com.dev.alex.Model.NonDbModel.PerformancePoint;
import com.dev.alex.Repository.CashHoldingRepository;
import com.dev.alex.Repository.MortgageRepository;
import com.dev.alex.Repository.RealEstatePropertyRepository;
import com.dev.alex.Service.HoldingsCompleteDataServiceImpl;
import com.dev.alex.Service.MortgageCalculationService;
import com.dev.alex.Service.PortfolioPerformanceServiceImpl;
import com.dev.alex.Service.RealEstateServiceImpl;
import com.dev.alex.Service.WealthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Cross-domain wealth examples use synthetic positions/properties and no database. */
class WealthServiceTests {
    private final RealEstatePropertyRepository properties = mock(RealEstatePropertyRepository.class);
    private final MortgageRepository mortgages = mock(MortgageRepository.class);
    private final CashHoldingRepository cash = mock(CashHoldingRepository.class);
    private final HoldingsCompleteDataServiceImpl holdings = mock(HoldingsCompleteDataServiceImpl.class);
    private final PortfolioPerformanceServiceImpl performance = mock(PortfolioPerformanceServiceImpl.class);
    private final RealEstateServiceImpl realEstate = new RealEstateServiceImpl();
    private final WealthServiceImpl wealth = new WealthServiceImpl();
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(realEstate, "calculations", new MortgageCalculationService());
        ReflectionTestUtils.setField(wealth, "holdings", holdings);
        ReflectionTestUtils.setField(wealth, "performance", performance);
        ReflectionTestUtils.setField(wealth, "properties", properties);
        ReflectionTestUtils.setField(wealth, "mortgages", mortgages);
        ReflectionTestUtils.setField(wealth, "cash", cash);
        ReflectionTestUtils.setField(wealth, "realEstate", realEstate);
        when(properties.findAllByPortfolioId("p")).thenReturn(List.of());
        when(mortgages.findAllByPortfolioId("p")).thenReturn(List.of());
        when(cash.findAllByPortfolioId("p")).thenReturn(List.of());
        when(holdings.getAllHoldingsByPortfolioId("p")).thenReturn(List.of());
        when(performance.getMonthlyHistory("p")).thenReturn(List.of());
    }

    @Test
    void propertyOnlyPortfolioHasEquityWithoutTradesOrInvestmentHistory() {
        var property = property("home", "EUR", "300000", today.minusYears(2), "1", "MORTGAGE_FREE");
        when(properties.findAllByPortfolioId("p")).thenReturn(List.of(property));
        var current = wealth.current("p");
        assertTrue(current.complete());
        assertEquals(0, current.investmentCount());
        assertTrue(current.investmentValueByCurrency().isEmpty());
        amount("300000", current.propertyEquityByCurrency().get("EUR"));
        var history = wealth.history("p", null, null);
        assertFalse(history.isEmpty());
        assertEquals(today, history.getLast().date());
        amount("300000", history.getFirst().propertyEquityByCurrency().get("EUR"));
        verify(properties, times(2)).findAllByPortfolioId("p");
        verify(mortgages, times(2)).findAllByPortfolioId("p");
    }

    @Test
    void pendingLoanSetupKeepsDebtAndEquityUnknownInsteadOfDebtFree() {
        var property = property("pending", "EUR", "300000", today.minusMonths(2), "1", "MORTGAGE_SETUP_PENDING");
        when(properties.findAllByPortfolioId("p")).thenReturn(List.of(property));
        var current = wealth.current("p");
        assertFalse(current.complete());
        assertEquals(List.of("pending"), current.missingPropertyIds());
        amount("300000", current.ownedPropertyValueByCurrency().get("EUR"));
        assertTrue(current.propertyEquityByCurrency().isEmpty());
        assertTrue(current.mortgagePrincipalByCurrency().isEmpty());
        assertNull(current.properties().getFirst().equity());
    }

    @Test
    void ownershipAndDebtAllocationAreIndependentAndNegativeEquitySurvives() {
        var shared = property("shared", "EUR", "300000", today.minusMonths(1), ".5", "MORTGAGE_RECORDED");
        var underwater = property("underwater", "USD", "160000", today.minusMonths(1), "1", "MORTGAGE_RECORDED");
        var sharedLoan = loan(shared, "180000", ".25", today.minusMonths(1));
        var underwaterLoan = loan(underwater, "180000", "1", today.minusMonths(1));
        when(properties.findAllByPortfolioId("p")).thenReturn(List.of(shared, underwater));
        when(mortgages.findAllByPortfolioId("p")).thenReturn(List.of(sharedLoan, underwaterLoan));
        var current = wealth.current("p");
        assertTrue(current.complete());
        amount("150000", current.ownedPropertyValueByCurrency().get("EUR"));
        amount("45000", current.mortgagePrincipalByCurrency().get("EUR"));
        amount("105000", current.propertyEquityByCurrency().get("EUR"));
        amount("-20000", current.propertyEquityByCurrency().get("USD"));
    }

    @Test
    void currenciesRemainNativeIncludingPenceQuotesAndCashIsSeparate() {
        var property = property("home", "EUR", "300000", today.minusMonths(1), "1", "MORTGAGE_FREE");
        when(properties.findAllByPortfolioId("p")).thenReturn(List.of(property));
        when(holdings.getAllHoldingsByPortfolioId("p")).thenReturn(List.of(
                position("uk", "GBP", "GBp", "12345"), position("us", "EUR", "USD", "150")));
        when(cash.findAllByPortfolioId("p")).thenReturn(List.of(
                new CashHolding("cash", "p", "CHF", bd("900"), today)));
        var result = wealth.current("p");
        assertEquals(2, result.investmentCount());
        amount("12345", result.investmentValueByCurrency().get("GBp"));
        amount("150", result.investmentValueByCurrency().get("USD"));
        amount("300000", result.propertyEquityByCurrency().get("EUR"));
        amount("900", result.manualCashByCurrency().get("CHF"));
        assertEquals(2, result.investmentValueByCurrency().size());
    }

    @Test
    void missingSecurityQuoteMakesCurrentWealthExplicitlyIncomplete() {
        when(holdings.getAllHoldingsByPortfolioId("p")).thenReturn(List.of(position("missing", "EUR", null, null)));
        var result = wealth.current("p");
        assertFalse(result.complete());
        assertEquals(List.of("missing"), result.missingInvestmentTickers());
        assertTrue(result.investmentValueByCurrency().isEmpty());
    }

    @Test
    void archivedPropertyRemainsInEarlierHistoryAndIsAbsentAfterArchiveDate() {
        LocalDate start = today.minusMonths(3).withDayOfMonth(1);
        LocalDate archive = today.minusMonths(1).withDayOfMonth(1);
        var property = property("sold", "EUR", "300000", start, "1", "MORTGAGE_FREE");
        property.setStatus("ARCHIVED"); property.setArchiveDate(archive);
        when(properties.findAllByPortfolioId("p")).thenReturn(List.of(property));
        assertTrue(wealth.current("p").propertyEquityByCurrency().isEmpty());
        var history = wealth.history("p", start, today);
        var before = history.stream().filter(p -> p.date().isBefore(archive)).findFirst().orElseThrow();
        amount("300000", before.propertyEquityByCurrency().get("EUR"));
        assertTrue(history.stream().filter(p -> !p.date().isBefore(archive)).allMatch(p -> p.propertyEquityByCurrency().isEmpty()));
    }

    @Test
    void historicalValuationsAndInvestmentSamplesNeverBorrowFutureValuesOrCash() {
        LocalDate firstMonth = today.minusMonths(3).withDayOfMonth(1);
        LocalDate later = firstMonth.plusMonths(2);
        var property = property("home", "EUR", "250000", firstMonth, "1", "MORTGAGE_FREE");
        property.getValuations().add(valuation("new", later, "300000"));
        when(properties.findAllByPortfolioId("p")).thenReturn(List.of(property));
        when(cash.findAllByPortfolioId("p")).thenReturn(List.of(new CashHolding("cash", "p", "EUR", bd("999999"), today)));
        when(performance.getMonthlyHistory("p")).thenReturn(List.of(
                new PerformancePoint(firstMonth.withDayOfMonth(firstMonth.lengthOfMonth()), bd("100"), Map.of("USD", bd("100"))),
                new PerformancePoint(later.withDayOfMonth(later.lengthOfMonth()), bd("200"), Map.of("USD", bd("200")))));
        var history = wealth.history("p", firstMonth, today);
        amount("250000", history.getFirst().propertyEquityByCurrency().get("EUR"));
        amount("100", history.getFirst().investmentValueByCurrency().get("USD"));
        amount("300000", history.getLast().propertyEquityByCurrency().get("EUR"));
        amount("200", history.getLast().investmentValueByCurrency().get("USD"));
        assertFalse(history.getFirst().investmentValueByCurrency().containsKey("EUR"));
        verifyNoInteractions(cash);
    }

    @Test
    void customPastUpperBoundDoesNotRelabelAnOlderInvestmentSampleAsANewValuation() {
        LocalDate start = today.minusMonths(3).withDayOfMonth(1);
        LocalDate firstMonthEnd = start.withDayOfMonth(start.lengthOfMonth());
        LocalDate secondMonth = start.plusMonths(1);
        LocalDate secondMonthEnd = secondMonth.withDayOfMonth(secondMonth.lengthOfMonth());
        when(performance.getMonthlyHistory("p")).thenReturn(List.of(
                new PerformancePoint(firstMonthEnd, bd("100"), Map.of("USD", bd("100"))),
                new PerformancePoint(secondMonthEnd, bd("200"), Map.of("USD", bd("200")))));
        var bounded = wealth.history("p", start, secondMonth.withDayOfMonth(15));
        assertEquals(1, bounded.size());
        assertEquals(firstMonthEnd, bounded.getFirst().date());
        amount("100", bounded.getFirst().investmentValueByCurrency().get("USD"));
        assertTrue(wealth.history("p", start, start.withDayOfMonth(15)).isEmpty());
    }

    @Test
    void propertyHistoryRequiresOpeningBankBalanceBeforeEquityBecomesKnown() {
        LocalDate start = today.minusMonths(3).withDayOfMonth(1);
        var property = property("home", "EUR", "300000", start, "1", "MORTGAGE_RECORDED");
        Mortgage loan = loan(property, "180000", "1", start.plusMonths(1));
        loan.setLoanStartDate(start);
        when(properties.findByIdAndPortfolioId("home", "p")).thenReturn(Optional.of(property));
        when(mortgages.findAllByPortfolioIdAndPropertyId("p", "home")).thenReturn(List.of(loan));
        var history = wealth.propertyHistory("p", "home");
        assertFalse(history.getFirst().complete());
        assertNull(history.getFirst().equity());
        assertEquals("UNKNOWN", history.getFirst().provenance());
        amount("120000", history.getLast().equity());
        assertTrue(history.getLast().complete());
    }

    @Test
    void unknownRateCreatesHistoricalEquityGapsWhileBankCheckpointRestoresAnExactPoint() {
        LocalDate start = today.minusMonths(4).withDayOfMonth(1);
        var property = property("home", "EUR", "300000", start, "1", "MORTGAGE_RECORDED");
        Mortgage loan = loan(property, "180000", "1", start);
        loan.setBalanceTrackingMode("SCHEDULE_ESTIMATE");
        Mortgage.RatePeriod rate = new Mortgage.RatePeriod();
        rate.setId("opening-rate"); rate.setEffectiveFrom(start); rate.setFixedUntil(start.plusMonths(2).minusDays(1));
        rate.setAnnualNominalRatePercent(BigDecimal.ZERO); rate.setMonthlyPayment(bd("100")); rate.setPaymentSource("BANK_AGREED");
        loan.getRatePeriods().clear(); loan.getRatePeriods().add(rate);
        LocalDate newCheckpoint = start.plusMonths(3);
        Mortgage.BalanceCheckpoint checkpoint = new Mortgage.BalanceCheckpoint();
        checkpoint.setId("reconciled"); checkpoint.setAsOfDate(newCheckpoint); checkpoint.setPrincipalBalance(bd("170000"));
        loan.getBalanceCheckpoints().add(checkpoint);
        when(properties.findAllByPortfolioId("p")).thenReturn(List.of(property));
        when(mortgages.findAllByPortfolioId("p")).thenReturn(List.of(loan));
        when(properties.findByIdAndPortfolioId("home", "p")).thenReturn(Optional.of(property));
        when(mortgages.findAllByPortfolioIdAndPropertyId("p", "home")).thenReturn(List.of(loan));

        var history = wealth.history("p", start, today);
        var beforeExpiry = history.stream().filter(p -> p.date().equals(start.plusMonths(1).withDayOfMonth(start.plusMonths(1).lengthOfMonth())))
                .findFirst().orElseThrow();
        amount("120100", beforeExpiry.propertyEquityByCurrency().get("EUR"));
        var gap = history.stream().filter(p -> p.date().equals(start.plusMonths(2).withDayOfMonth(start.plusMonths(2).lengthOfMonth())))
                .findFirst().orElseThrow();
        assertFalse(gap.complete());
        assertEquals(List.of("home"), gap.missingPropertyIds());
        assertTrue(gap.propertyEquityByCurrency().isEmpty());
        assertTrue(gap.mortgagePrincipalByCurrency().isEmpty());

        var propertyHistory = wealth.propertyHistory("p", "home");
        var paused = propertyHistory.stream().filter(p -> p.date().equals(gap.date())).findFirst().orElseThrow();
        assertFalse(paused.complete()); assertNull(paused.equity()); assertNull(paused.attributedMortgagePrincipal());
        assertEquals("PAUSED", paused.provenance());
        var confirmed = propertyHistory.stream().filter(p -> p.date().equals(newCheckpoint)).findFirst().orElseThrow();
        assertTrue(confirmed.complete()); amount("130000", confirmed.equity());
        assertEquals("CONFIRMED", confirmed.provenance());
        // The current card can still show a labelled, frozen last-known estimate.
        amount("130000", wealth.current("p").propertyEquityByCurrency().get("EUR"));
    }

    @Test
    void zeroAllocatedDebtDoesNotGapKnownPropertyEquityWhenWholeLoanRatesAreUnknown() {
        LocalDate start = today.minusMonths(2).withDayOfMonth(1);
        var property = property("home", "EUR", "300000", start, "1", "MORTGAGE_RECORDED");
        Mortgage loan = loan(property, "180000", "0", start);
        loan.getRatePeriods().clear();
        when(properties.findAllByPortfolioId("p")).thenReturn(List.of(property));
        when(mortgages.findAllByPortfolioId("p")).thenReturn(List.of(loan));
        when(properties.findByIdAndPortfolioId("home", "p")).thenReturn(Optional.of(property));
        when(mortgages.findAllByPortfolioIdAndPropertyId("p", "home")).thenReturn(List.of(loan));
        var history = wealth.history("p", start, today);
        assertTrue(history.stream().allMatch(point -> point.complete() && point.missingPropertyIds().isEmpty()));
        amount("300000", history.getLast().propertyEquityByCurrency().get("EUR"));
        amount("0", history.getLast().mortgagePrincipalByCurrency().get("EUR"));
        var propertyHistory = wealth.propertyHistory("p", "home");
        assertTrue(propertyHistory.getLast().complete());
        amount("300000", propertyHistory.getLast().equity());
    }

    @Test
    void twoLoansOnOnePropertySumAndOnePausedLoanMakesThatMonthUnknown() {
        LocalDate start = today.minusMonths(4).withDayOfMonth(1);
        var property = property("home", "EUR", "300000", start, "1", "MORTGAGE_RECORDED");
        Mortgage mortgage = loan(property, "180000", "1", start);
        Mortgage downPayment = loan(property, "20000", "1", start);
        downPayment.setId("loan-home-down-payment"); downPayment.setName("Down-payment loan");
        // Its rate is known for two months only; the statement-only mortgage stays known throughout.
        downPayment.setBalanceTrackingMode("SCHEDULE_ESTIMATE");
        downPayment.getRatePeriods().getFirst().setFixedUntil(start.plusMonths(2).minusDays(1));
        when(properties.findAllByPortfolioId("p")).thenReturn(List.of(property));
        when(mortgages.findAllByPortfolioId("p")).thenReturn(List.of(mortgage, downPayment));
        when(properties.findByIdAndPortfolioId("home", "p")).thenReturn(Optional.of(property));
        when(mortgages.findAllByPortfolioIdAndPropertyId("p", "home")).thenReturn(List.of(mortgage, downPayment));

        // Today shows the frozen last-known balance of the paused loan beside the mortgage.
        amount("199800", wealth.current("p").mortgagePrincipalByCurrency().get("EUR"));
        var history = wealth.history("p", start, today);
        LocalDate bothKnown = start.plusMonths(1).withDayOfMonth(start.plusMonths(1).lengthOfMonth());
        amount("100100", history.stream().filter(p -> p.date().equals(bothKnown)).findFirst().orElseThrow()
                .propertyEquityByCurrency().get("EUR"));
        LocalDate gapDate = start.plusMonths(3).withDayOfMonth(start.plusMonths(3).lengthOfMonth());
        var gap = history.stream().filter(p -> p.date().equals(gapDate)).findFirst().orElseThrow();
        assertFalse(gap.complete());
        assertEquals(List.of("home"), gap.missingPropertyIds());

        var paused = wealth.propertyHistory("p", "home").stream().filter(p -> p.date().equals(gapDate)).findFirst().orElseThrow();
        assertEquals("PAUSED", paused.provenance());
        assertTrue(paused.warnings().stream().anyMatch(w -> w.startsWith("Down-payment loan: ")));
    }

    private RealEstateProperty property(String id, String currency, String value, LocalDate start, String share, String financing) {
        RealEstateProperty p = new RealEstateProperty();
        p.setId(id); p.setPortfolioId("p"); p.setCurrency(currency); p.setName("Synthetic " + id);
        p.setOwnershipShare(bd(share)); p.setFinancingStatus(financing); p.setTrackingStartDate(start); p.setStatus("ACTIVE");
        p.setValuations(new ArrayList<>(List.of(valuation("opening", start, value))));
        return p;
    }

    private Mortgage loan(RealEstateProperty property, String balance, String allocation, LocalDate opening) {
        Mortgage m = new Mortgage();
        m.setId("loan-" + property.getId()); m.setPortfolioId("p"); m.setPropertyId(property.getId());
        m.setCurrency(property.getCurrency()); m.setLoanStartDate(opening); m.setFirstPaymentDate(opening.plusMonths(1));
        m.setMaturityDate(opening.plusYears(20)); m.setPaymentDay(opening.getDayOfMonth());
        m.setDebtAllocationShare(bd(allocation)); m.setBalanceTrackingMode("STATEMENT_ONLY"); m.setStatus("ACTIVE");
        Mortgage.BalanceCheckpoint cp = new Mortgage.BalanceCheckpoint();
        cp.setId("opening"); cp.setAsOfDate(opening); cp.setPrincipalBalance(bd(balance)); cp.setSource("BANK_STATEMENT");
        m.setBalanceCheckpoints(new ArrayList<>(List.of(cp)));
        Mortgage.RatePeriod rate = new Mortgage.RatePeriod();
        rate.setId("known-rate"); rate.setEffectiveFrom(opening); rate.setFixedUntil(m.getMaturityDate());
        rate.setAnnualNominalRatePercent(BigDecimal.ZERO); rate.setMonthlyPayment(bd("100")); rate.setPaymentSource("BANK_AGREED");
        m.setRatePeriods(new ArrayList<>(List.of(rate)));
        return m;
    }

    private RealEstateProperty.Valuation valuation(String id, LocalDate date, String value) {
        var valuation = new RealEstateProperty.Valuation();
        valuation.setId(id); valuation.setEffectiveDate(date); valuation.setFullPropertyValue(bd(value)); valuation.setSource("OWNER_ESTIMATE");
        return valuation;
    }

    private HoldingsCompleteData position(String ticker, String book, String quote, String value) {
        var holding = new HoldingsCompleteData();
        holding.setTicker(ticker); holding.setCurrency(book); holding.setQuoteCurrency(quote);
        holding.setCurrentTotalValue(value == null ? null : bd(value));
        return holding;
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static void amount(String expected, BigDecimal actual) {
        assertNotNull(actual); assertEquals(0, bd(expected).compareTo(actual), "Expected " + expected + " but got " + actual);
    }
}
