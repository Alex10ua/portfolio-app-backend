package com.dev.alex.Service.Ai;

import com.dev.alex.Model.Mortgage;
import com.dev.alex.Model.RealEstateProperty;
import com.dev.alex.Model.NonDbModel.WealthData;
import com.dev.alex.Model.UserSettings;
import com.dev.alex.Repository.MortgageRepository;
import com.dev.alex.Repository.RealEstatePropertyRepository;
import com.dev.alex.Service.MortgageCalculationService;
import com.dev.alex.Service.HoldingServiceImpl;
import com.dev.alex.Service.RealEstateServiceImpl;
import com.dev.alex.Service.WealthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic loans only; the read contract must retain uncertainty and each loan's independent debt share. */
class AiRealEstateAssemblerTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);
    private AiRealEstateAssembler assembler;
    private RealEstateServiceImpl realEstate;
    private MortgageCalculationService calculations;
    private WealthServiceImpl wealth;
    private RealEstatePropertyRepository properties;
    private MortgageRepository mortgages;
    private AiEnvelopeService envelope;
    private HoldingServiceImpl holdings;

    @BeforeEach
    void setup() {
        assembler = new AiRealEstateAssembler();
        assembler.clock = CLOCK;
        realEstate = mock(RealEstateServiceImpl.class);
        calculations = mock(MortgageCalculationService.class);
        wealth = mock(WealthServiceImpl.class);
        properties = mock(RealEstatePropertyRepository.class);
        mortgages = mock(MortgageRepository.class);
        envelope = mock(AiEnvelopeService.class);
        holdings = mock(HoldingServiceImpl.class);
        ReflectionTestUtils.setField(assembler, "realEstate", realEstate);
        ReflectionTestUtils.setField(assembler, "calculations", calculations);
        ReflectionTestUtils.setField(assembler, "wealth", wealth);
        ReflectionTestUtils.setField(assembler, "propertyRepository", properties);
        ReflectionTestUtils.setField(assembler, "mortgageRepository", mortgages);
        ReflectionTestUtils.setField(assembler, "envelopeService", envelope);
        ReflectionTestUtils.setField(assembler, "holdingService", holdings);
    }

    @Test
    void propertyListIncludesActiveAndClosedLoansAndNetsBoundSavingsBeforeDebtAllocation() {
        RealEstateProperty property = property("home", "ACTIVE");
        property.setOwnershipShare(bd("0.25"));
        Mortgage first = loan("mortgage", "home", "ACTIVE", "0.5");
        first.setBuildingSavings(new Mortgage.BuildingSavings());
        Mortgage second = loan("deposit-loan", "home", "ACTIVE", "1");
        Mortgage closed = loan("old-loan", "home", "CLOSED", "1");
        var firstSummary = summary("100000", "20000", "CONFIRMED");
        var secondSummary = summary("10000", null, "ESTIMATED");
        var closedSummary = summary("0", null, "CONFIRMED");
        List<Mortgage> linked = List.of(first, second, closed);
        when(properties.findAllByPortfolioId("portfolio")).thenReturn(List.of(property));
        when(mortgages.findAllByPortfolioId("portfolio")).thenReturn(linked);
        when(realEstate.viewFor(property, linked, TODAY, false)).thenReturn(new RealEstateServiceImpl.PropertyView(
                property, linked, null, bd("400000"), bd("100000"), bd("50000"), bd("50000"), true,
                Map.of("mortgage", firstSummary, "deposit-loan", secondSummary), null, bd("10000")));
        when(calculations.summarize(closed, TODAY, false)).thenReturn(closedSummary);

        var row = assembler.properties("portfolio", "all").getFirst();

        assertEquals(3, row.loans().size());
        assertEquals("CLOSED", row.loans().get(2).status());
        assertEquals("BUILDING_SAVINGS", row.loans().getFirst().loanType());
        amount("80000", row.loans().getFirst().netDebt());
        amount("40000", row.loans().getFirst().attributedNetDebt());
        amount("50000", row.attributedMortgagePrincipal());
        amount("50000", row.equity());
        assertEquals("ESTIMATED", row.loans().get(1).summary().balance().provenance());
        assertFalse(row.loans().getFirst().projectionIncluded());
    }

    @Test
    void loanListFiltersStatusAndPropertyWithoutCrossPortfolioJoins() {
        RealEstateProperty property = property("home", "ACTIVE");
        Mortgage active = loan("active", "home", "ACTIVE", "1");
        Mortgage closed = loan("closed", "home", "CLOSED", "1");
        Mortgage otherProperty = loan("other-property", "other", "ACTIVE", "1");
        Mortgage otherPortfolio = loan("foreign", "home", "ACTIVE", "1");
        otherPortfolio.setPortfolioId("another-portfolio");
        when(realEstate.requireProperty("portfolio", "home")).thenReturn(property);
        when(mortgages.findAllByPortfolioId("portfolio"))
                .thenReturn(List.of(active, closed, otherProperty, otherPortfolio));
        when(calculations.summarize(active, TODAY, false)).thenReturn(summary(null, null, "UNKNOWN"));
        var rows = assembler.loans("portfolio", "home", "active");
        assertEquals(List.of("active"), rows.stream().map(r -> r.loanId()).toList());
        assertNull(rows.getFirst().netDebt());
        assertNull(rows.getFirst().attributedNetDebt());
        verify(calculations, never()).summarize(eq(otherPortfolio), any(), anyBoolean());
        assertThrows(IllegalArgumentException.class, () -> assembler.loans("portfolio", null, "BOGUS"));
    }

    @Test
    void archivedPropertiesAreFilteredBeforeCalculation() {
        RealEstateProperty active = property("active", "ACTIVE");
        RealEstateProperty archived = property("archived", "ARCHIVED");
        when(properties.findAllByPortfolioId("portfolio")).thenReturn(List.of(active, archived));
        when(mortgages.findAllByPortfolioId("portfolio")).thenReturn(List.of());
        when(realEstate.viewFor(archived, List.of(), TODAY, false)).thenReturn(new RealEstateServiceImpl.PropertyView(
                archived, List.of(), null, null, null, null, null, false, Map.of(), null, null));
        assertEquals(List.of("archived"), assembler.properties("portfolio", "ARCHIVED").stream()
                .map(r -> r.propertyId()).toList());
        verify(realEstate, never()).viewFor(eq(active), anyList(), any(), anyBoolean());
    }

    @Test
    void scheduleCapsRowsAndHandlesAnOffsetBeyondTheEndWithoutOverflow() {
        Mortgage loan = loan("loan", "home", "ACTIVE", "1");
        when(realEstate.requireMortgage("portfolio", "home", "loan")).thenReturn(loan);
        List<MortgageCalculationService.Installment> rows = new ArrayList<>();
        for (int i = 0; i < 650; i++) rows.add(installment(TODAY.plusDays(i)));
        when(realEstate.schedule("portfolio", "home", "loan", null, null)).thenReturn(rows);
        var first = assembler.schedule("portfolio", "home", "loan", null, null, 0, 9000);
        assertEquals(600, first.limit());
        assertEquals(600, first.items().size());
        assertEquals(650, first.total());
        assertTrue(first.hasMore());
        var tail = assembler.schedule("portfolio", "home", "loan", null, null, 600, 600);
        assertEquals(50, tail.items().size());
        assertFalse(tail.hasMore());
        var beyond = assembler.schedule("portfolio", "home", "loan", null, null, Integer.MAX_VALUE, 600);
        assertTrue(beyond.items().isEmpty());
        assertFalse(beyond.hasMore());
    }

    @Test
    void wealthRetainsHiddenPropertiesAndMissingFlagsAndHistoryKeepsEndpoints() {
        var settings = new UserSettings.PortfolioSettings();
        settings.setHideRealEstate(true);
        when(envelope.portfolioSettings("user", "portfolio")).thenReturn(Optional.of(settings));
        when(wealth.current("portfolio")).thenReturn(new WealthData.Summary(TODAY,
                Map.of("USD", bd("50")), Map.of("EUR", bd("100")), Map.of(), Map.of(),
                Map.of("EUR", bd("5")), List.of(), 1, false, List.of("unknown-home"), List.of()));
        var snapshot = assembler.wealth("portfolio", "user");
        assertTrue(snapshot.realEstateHiddenInUi());
        assertFalse(snapshot.complete());
        assertEquals(List.of("unknown-home"), snapshot.missingPropertyIds());
        amount("100", snapshot.ownedPropertyValueByCurrency().get("EUR"));
        assertEquals("ALL_ACTIVE_PROPERTIES_REGARDLESS_OF_UI_VISIBILITY", snapshot.inclusionPolicy());
        List<WealthData.HistoryPoint> history = new ArrayList<>();
        for (int i = 0; i < 700; i++) history.add(new WealthData.HistoryPoint(TODAY.minusMonths(699 - i),
                Map.of(), Map.of(), Map.of(), Map.of(), true, List.of(), List.of(), List.of()));
        when(wealth.history("portfolio", null, null)).thenReturn(history);
        var sampled = assembler.wealthHistory("portfolio", null, null, 9999);
        assertEquals(600, sampled.size());
        assertEquals(history.getFirst(), sampled.getFirst());
        assertEquals(history.getLast(), sampled.getLast());
        assertEquals(List.of(history.getLast()), assembler.wealthHistory("portfolio", null, null, 1));
    }

    @Test
    void propertyOnlyPortfolioUsesItsPropertyCurrencyAsTheDisplayFallback() {
        when(holdings.getAllHoldingsByPortfolioId("portfolio")).thenReturn(List.of());
        when(properties.findAllByPortfolioId("portfolio")).thenReturn(List.of(property("home", "ACTIVE")));
        when(envelope.baseCurrency("user", "portfolio", List.of("EUR"))).thenReturn("EUR");
        assertEquals("EUR", assembler.baseCurrency("user", "portfolio"));
    }

    @Test
    void loanDetailRejectsAnIdThatDoesNotBelongToTheRequestedProperty() {
        RealEstateServiceImpl actual = new RealEstateServiceImpl();
        ReflectionTestUtils.setField(actual, "propertyRepository", properties);
        ReflectionTestUtils.setField(actual, "mortgageRepository", mortgages);
        ReflectionTestUtils.setField(assembler, "realEstate", actual);
        when(properties.findByIdAndPortfolioId("home", "portfolio")).thenReturn(Optional.of(property("home", "ACTIVE")));
        when(mortgages.findByIdAndPortfolioIdAndPropertyId("unrelated-loan", "portfolio", "home"))
                .thenReturn(Optional.empty());
        var error = assertThrows(RealEstateServiceImpl.RealEstateException.class,
                () -> assembler.loan("portfolio", "home", "unrelated-loan"));
        assertEquals(404, error.getStatus());
        verifyNoInteractions(calculations);
    }

    @Test
    void bothPreviewReadsLeaveTheStoredLoanUnchangedAndNeverSave() {
        Mortgage loan = loan("loan", "home", "ACTIVE", "0.5");
        loan.setBalanceTrackingMode("SCHEDULE_ESTIMATE");
        loan.setLoanStartDate(LocalDate.of(2026, 11, 1));
        loan.setFirstPaymentDate(LocalDate.of(2026, 12, 1));
        loan.setMaturityDate(LocalDate.of(2036, 11, 1));
        loan.setPaymentDay(1);
        loan.setOriginalPrincipal(bd("100000"));
        loan.getBalanceCheckpoints().add(new Mortgage.BalanceCheckpoint("opening", loan.getLoanStartDate(),
                bd("100000"), "BANK", null, Instant.now(CLOCK)));
        loan.getRatePeriods().add(new Mortgage.RatePeriod("rate", loan.getLoanStartDate(), loan.getMaturityDate(),
                bd("4"), bd("1012.45"), "BANK_AGREED", loan.getLoanStartDate(), bd("100000"), 120,
                "USER", Instant.now(CLOCK)));
        RealEstateServiceImpl actual = new RealEstateServiceImpl();
        ReflectionTestUtils.setField(actual, "clock", CLOCK);
        ReflectionTestUtils.setField(actual, "propertyRepository", properties);
        ReflectionTestUtils.setField(actual, "mortgageRepository", mortgages);
        ReflectionTestUtils.setField(actual, "calculations", new MortgageCalculationService(CLOCK));
        ReflectionTestUtils.setField(assembler, "realEstate", actual);
        when(properties.findByIdAndPortfolioId("home", "portfolio")).thenReturn(Optional.of(property("home", "ACTIVE")));
        when(mortgages.findByIdAndPortfolioIdAndPropertyId("loan", "portfolio", "home")).thenReturn(Optional.of(loan));
        var originalRate = loan.getRatePeriods().getFirst();
        var extra = assembler.extraPaymentPreview("portfolio", "home", "loan", TODAY, bd("5000"));
        var rate = assembler.ratePreview("portfolio", "home", "loan", TODAY, null, bd("5"), null, null);
        assertFalse(extra.saved());
        assertFalse(rate.saved());
        assertNotNull(extra.preview().lowerInstallment());
        assertFalse(rate.preview().schedule().isEmpty());
        assertTrue(loan.getPayments().isEmpty());
        assertEquals(1, loan.getRatePeriods().size());
        assertSame(originalRate, loan.getRatePeriods().getFirst());
        amount("4", originalRate.getAnnualNominalRatePercent());
        assertEquals(loan.getMaturityDate(), originalRate.getFixedUntil());
        verify(mortgages, never()).save(any());
        verify(properties, never()).save(any());
    }

    private static RealEstateProperty property(String id, String status) {
        RealEstateProperty property = new RealEstateProperty();
        property.setId(id); property.setPortfolioId("portfolio"); property.setName(id);
        property.setCurrency("EUR"); property.setStatus(status);
        property.setFinancingStatus("MORTGAGE_RECORDED"); property.setOwnershipShare(BigDecimal.ONE);
        return property;
    }

    private static Mortgage loan(String id, String propertyId, String status, String share) {
        Mortgage loan = new Mortgage();
        loan.setId(id); loan.setPortfolioId("portfolio"); loan.setPropertyId(propertyId); loan.setName(id);
        loan.setCurrency("EUR"); loan.setStatus(status); loan.setDebtAllocationShare(bd(share));
        loan.setLoanStartDate(TODAY.minusYears(1));
        return loan;
    }

    private static MortgageCalculationService.Summary summary(String principal, String savings, String provenance) {
        return new MortgageCalculationService.Summary(new MortgageCalculationService.Balance(
                principal == null ? null : bd(principal), provenance, TODAY, TODAY, false, false, List.of(),
                savings == null ? null : bd(savings)), null, null, null, null, null, 0,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static MortgageCalculationService.Installment installment(LocalDate date) {
        return new MortgageCalculationService.Installment("INSTALLMENT", date, date, null, null, null,
                null, null, null, "PROJECTED", null, null, null, null);
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static void amount(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, bd(expected).compareTo(actual));
    }
}
