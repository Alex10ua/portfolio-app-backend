package com.dev.alex.Service;

import com.dev.alex.Model.Mortgage;
import com.dev.alex.Model.RealEstateProperty;
import com.dev.alex.Model.NonDbModel.RealEstateCommands.*;
import com.dev.alex.Repository.MortgageRepository;
import com.dev.alex.Repository.RealEstatePropertyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RealEstateServiceTests {
    private RealEstateServiceImpl service;
    private RealEstatePropertyRepository properties;
    private MortgageRepository mortgages;
    private final Map<String, RealEstateProperty> propertyStore = new HashMap<>();
    private final Map<String, Mortgage> mortgageStore = new HashMap<>();
    private static final String PID = "test portfolio";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @BeforeEach
    void setup() {
        propertyStore.clear(); mortgageStore.clear();
        properties = mock(RealEstatePropertyRepository.class);
        mortgages = mock(MortgageRepository.class);
        service = new RealEstateServiceImpl();
        Clock clock = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC);
        service.clock = clock;
        ReflectionTestUtils.setField(service, "propertyRepository", properties);
        ReflectionTestUtils.setField(service, "mortgageRepository", mortgages);
        ReflectionTestUtils.setField(service, "calculations", new MortgageCalculationService(clock));
        when(properties.findByIdAndPortfolioId(anyString(), anyString())).thenAnswer(invocation -> {
            RealEstateProperty p = propertyStore.get(invocation.getArgument(0));
            return p != null && p.getPortfolioId().equals(invocation.getArgument(1)) ? Optional.of(p) : Optional.empty();
        });
        when(properties.findAllByPortfolioId(anyString())).thenAnswer(invocation -> propertyStore.values().stream()
                .filter(p -> p.getPortfolioId().equals(invocation.getArgument(0))).toList());
        when(properties.insert(any(RealEstateProperty.class))).thenAnswer(invocation -> {
            RealEstateProperty p = invocation.getArgument(0); p.setVersion(0L); propertyStore.put(p.getId(), p); return p;
        });
        when(properties.save(any(RealEstateProperty.class))).thenAnswer(invocation -> {
            RealEstateProperty p = invocation.getArgument(0); p.setVersion(p.getVersion() + 1); propertyStore.put(p.getId(), p); return p;
        });
        when(mortgages.findAllByPortfolioIdAndPropertyId(anyString(), anyString())).thenAnswer(invocation -> mortgageStore.values().stream()
                .filter(m -> m.getPortfolioId().equals(invocation.getArgument(0)) && m.getPropertyId().equals(invocation.getArgument(1))).toList());
        when(mortgages.findAllByPortfolioId(anyString())).thenAnswer(invocation -> mortgageStore.values().stream()
                .filter(m -> m.getPortfolioId().equals(invocation.getArgument(0))).toList());
        when(mortgages.findByIdAndPortfolioIdAndPropertyId(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            Mortgage m = mortgageStore.get(invocation.getArgument(0));
            return m != null && m.getPortfolioId().equals(invocation.getArgument(1)) && m.getPropertyId().equals(invocation.getArgument(2))
                    ? Optional.of(m) : Optional.empty();
        });
        when(mortgages.insert(any(Mortgage.class))).thenAnswer(invocation -> {
            Mortgage m = invocation.getArgument(0); m.setVersion(0L); mortgageStore.put(m.getId(), m); return m;
        });
        when(mortgages.save(any(Mortgage.class))).thenAnswer(invocation -> {
            Mortgage m = invocation.getArgument(0); m.setVersion(m.getVersion() + 1); mortgageStore.put(m.getId(), m); return m;
        });
        doAnswer(invocation -> propertyStore.remove(invocation.<RealEstateProperty>getArgument(0).getId()))
                .when(properties).delete(any(RealEstateProperty.class));
        doAnswer(invocation -> mortgageStore.remove(invocation.<Mortgage>getArgument(0).getId()))
                .when(mortgages).delete(any(Mortgage.class));
        doAnswer(invocation -> { invocation.<Iterable<Mortgage>>getArgument(0).forEach(m -> mortgageStore.remove(m.getId())); return null; })
                .when(mortgages).deleteAll(anyIterable());
    }

    @Test
    void deletingPropertyRemovesItsMortgagesAndRetryIsSafe() {
        Mortgage loan = createLoan();
        RealEstateProperty p = propertyStore.get(loan.getPropertyId());
        var stale = assertThrows(RealEstateServiceImpl.RealEstateException.class, () -> service.deleteProperty(PID, p.getId(), 99L));
        assertEquals(409, stale.getStatus());
        service.deleteProperty(PID, p.getId(), p.getVersion());
        assertTrue(propertyStore.isEmpty());
        assertTrue(mortgageStore.isEmpty());
        assertDoesNotThrow(() -> service.deleteProperty(PID, p.getId(), p.getVersion()));
    }

    @Test
    void deletingActiveMortgageNeverInfersMortgageFree() {
        Mortgage loan = createLoan();
        var missingChoice = assertThrows(RealEstateServiceImpl.RealEstateException.class, () ->
                service.deleteMortgage(PID, loan.getPropertyId(), loan.getId(), loan.getVersion(), null));
        assertEquals(400, missingChoice.getStatus());
        assertEquals(1, mortgageStore.size());
        var pending = service.deleteMortgage(PID, loan.getPropertyId(), loan.getId(), loan.getVersion(), "MORTGAGE_SETUP_PENDING");
        assertTrue(mortgageStore.isEmpty());
        assertEquals("MORTGAGE_SETUP_PENDING", pending.property().getFinancingStatus());
        assertFalse(pending.complete()); assertNull(pending.equity());
    }

    @Test
    void deletingMistakenMortgageCanLeavePropertyMortgageFree() {
        Mortgage loan = createLoan();
        var free = service.deleteMortgage(PID, loan.getPropertyId(), loan.getId(), loan.getVersion(), "MORTGAGE_FREE");
        assertEquals("MORTGAGE_FREE", free.property().getFinancingStatus());
        assertTrue(free.complete());
        assertEquals(0, new BigDecimal("300000").compareTo(free.equity()));
    }

    @Test
    void mortgageFreeDeleteRetryRepairsAnUnacknowledgedParentTransition() {
        Mortgage loan = createLoan();
        RealEstateProperty property = propertyStore.get(loan.getPropertyId());
        property.setFinancingStatus("MORTGAGE_SETUP_PENDING");
        mortgageStore.remove(loan.getId()); // the delete committed but its parent update did not
        var result = service.deleteMortgage(PID, property.getId(), loan.getId(), loan.getVersion(), "MORTGAGE_FREE");
        assertEquals("MORTGAGE_FREE", result.property().getFinancingStatus());
        assertTrue(result.complete());
        assertEquals(0, new BigDecimal("300000").compareTo(result.equity()));
    }

    @Test
    void deletingTheOnlyClosedMortgageLeavesPropertyMortgageFree() {
        Mortgage loan = createLoan();
        service.checkpoint(PID, loan.getPropertyId(), loan.getId(), null,
                new BalanceCommand(loan.getVersion(), TODAY, BigDecimal.ZERO, "BANK_STATEMENT", "Paid off"), "paid-off");
        service.close(PID, loan.getPropertyId(), loan.getId(), new DateCommand(loan.getVersion(), TODAY), "close");
        var view = service.deleteMortgage(PID, loan.getPropertyId(), loan.getId(), loan.getVersion(), null);
        assertEquals("MORTGAGE_FREE", view.property().getFinancingStatus());
        assertTrue(view.complete());
    }

    @Test
    void pendingFinancingNeverLooksDebtFree() {
        var view = service.createProperty(PID, propertyCommand("MORTGAGE_SETUP_PENDING", "300000", "1"), "property-pending");
        assertEquals(new BigDecimal("300000"), view.ownedValue());
        assertFalse(view.complete()); assertNull(view.equity()); assertNull(view.attributedMortgagePrincipal());
    }

    @Test
    void createReplayDoesNotDuplicateAndChangedInputConflicts() {
        var command = propertyCommand("MORTGAGE_FREE", "300000", "1");
        var first = service.createProperty(PID, command, "same-key");
        var repeated = service.createProperty(PID, command, "same-key");
        assertEquals(first.property().getId(), repeated.property().getId());
        verify(properties, times(1)).insert(any(RealEstateProperty.class));
        var conflict = assertThrows(RealEstateServiceImpl.RealEstateException.class, () ->
                service.createProperty(PID, propertyCommand("MORTGAGE_FREE", "310000", "1"), "same-key"));
        assertEquals(409, conflict.getStatus());
    }

    @Test
    void propertyRetryTreatsEquivalentDecimalScalesAsTheSameInput() {
        var first = service.createProperty(PID, propertyCommand("MORTGAGE_FREE", "300000", "1"), "same-decimals");
        var repeated = service.createProperty(PID, propertyCommand("MORTGAGE_FREE", "300000.00", "1.000"), "same-decimals");
        assertEquals(first.property().getId(), repeated.property().getId());
        verify(properties, times(1)).insert(any(RealEstateProperty.class));
    }

    @Test
    void unchangedRetryWithAnOlderStoredFingerprintStillWorks() throws Exception {
        var command = propertyCommand("MORTGAGE_FREE", "300000", "1");
        var first = service.createProperty(PID, command, "legacy-retry");
        String legacy = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(command.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        first.property().setCreationFingerprint(legacy);
        assertEquals(first.property().getId(), service.createProperty(PID, command, "legacy-retry").property().getId());
        verify(properties, times(1)).insert(any(RealEstateProperty.class));
    }

    @Test
    void propertyLookupIsConstrainedToPortfolio() {
        var property = service.createProperty(PID, propertyCommand("MORTGAGE_FREE", "300000", "1"), "property").property();
        var error = assertThrows(RealEstateServiceImpl.RealEstateException.class, () ->
                service.requireProperty("another portfolio", property.getId()));
        assertEquals(404, error.getStatus());
    }

    @Test
    void coOwnershipAndDebtAllocationAreIndependent() {
        var property = service.createProperty(PID, propertyCommand("MORTGAGE_SETUP_PENDING", "300000", "0.5"), "property").property();
        var result = service.createMortgage(PID, property.getId(), mortgageCommand(property, "0.25"), "loan");
        assertTrue(result.complete());
        assertEquals(0, new BigDecimal("45000").compareTo(result.attributedMortgagePrincipal()));
        assertEquals(0, new BigDecimal("105000").compareTo(result.equity()));
        assertEquals("MORTGAGE_RECORDED", result.property().getFinancingStatus());
        assertEquals(new BigDecimal("891.38"), result.mortgages().getFirst().getRatePeriods().getFirst().getMonthlyPayment());
    }

    @Test
    void setupReadBetweenInstallmentsStoresTheSameCalculatedInstallment() {
        // The same loan read on 20 Oct, after the 1 Oct installment: principal and remaining term are unchanged.
        var property = service.createProperty(PID, propertyCommand("MORTGAGE_SETUP_PENDING", "300000", "1"), "property").property();
        var valid = mortgageCommand(property, "1");
        var midMonth = new MortgageCommand(null, property.getVersion(), valid.name(), valid.lender(), valid.currency(), valid.originalPrincipal(),
                valid.loanStartDate(), valid.firstPaymentDate(), valid.maturityDate(), valid.paymentDay(), valid.debtAllocationShare(),
                valid.balanceTrackingMode(), new BalanceCommand(null, LocalDate.of(2026, 10, 20), new BigDecimal("180000"), "BANK_STATEMENT", null),
                new RateCommand(null, LocalDate.of(2026, 10, 20), LocalDate.of(2029, 9, 30), new BigDecimal("4"), null, null));
        var result = service.createMortgage(PID, property.getId(), midMonth, "loan");
        assertEquals(new BigDecimal("891.38"), result.mortgages().getFirst().getRatePeriods().getFirst().getMonthlyPayment());
        assertFalse(onlySummary(result).balance().partialPeriodUnsupported());
    }

    @Test
    void negativeEquityIsRetained() {
        var p = service.createProperty(PID, propertyCommand("MORTGAGE_SETUP_PENDING", "160000", "1"), "property").property();
        var result = service.createMortgage(PID, p.getId(), mortgageCommand(p, "1"), "loan");
        assertEquals(0, new BigDecimal("-20000").compareTo(result.equity()));
    }

    @Test
    void futureValuationDoesNotLeakIntoEarlierView() {
        var p = service.createProperty(PID, propertyCommand("MORTGAGE_FREE", "300000", "1"), "property").property();
        p.getValuations().add(new RealEstateProperty.Valuation("later", TODAY, new BigDecimal("400000"), "APPRAISAL", null, Instant.now()));
        var old = service.viewFor(p, List.of(), LocalDate.of(2026, 10, 2), false);
        assertEquals(0, new BigDecimal("300000").compareTo(old.equity()));
    }

    @Test
    void missingValuationDoesNotDiscardKnownMortgageDebt() {
        Mortgage loan = createLoan();
        RealEstateProperty property = propertyStore.get(loan.getPropertyId());
        property.getValuations().getFirst().setEffectiveDate(TODAY);
        var incomplete = service.viewFor(property, List.of(loan), LocalDate.of(2026, 10, 2), false);
        assertFalse(incomplete.complete());
        assertNull(incomplete.ownedValue()); assertNull(incomplete.equity());
        assertEquals(0, new BigDecimal("180000").compareTo(incomplete.attributedMortgagePrincipal()));
    }

    @Test
    void zeroDebtAllocationRemainsExactWhenTheWholeLoanBalanceIsUnknown() {
        Mortgage loan = createLoan();
        RealEstateProperty property = propertyStore.get(loan.getPropertyId());
        loan.getBalanceCheckpoints().getFirst().setAsOfDate(TODAY);
        LocalDate earlier = LocalDate.of(2026, 10, 2);
        assertFalse(service.viewFor(property, List.of(loan), earlier, false).complete());
        loan.setDebtAllocationShare(BigDecimal.ZERO);
        var result = service.viewFor(property, List.of(loan), earlier, false);
        assertTrue(result.complete());
        assertEquals(0, result.attributedMortgagePrincipal().signum());
        assertEquals(0, new BigDecimal("300000").compareTo(result.equity()));
        assertNull(onlySummary(result).balance().principalBalance());
        assertFalse(onlySummary(result).balance().warnings().isEmpty());
    }

    @Test
    void missingOpeningBankBalanceCannotFinishSetup() {
        var p = service.createProperty(PID, propertyCommand("MORTGAGE_SETUP_PENDING", "300000", "1"), "property").property();
        var valid = mortgageCommand(p, "1");
        var missing = new MortgageCommand(null, p.getVersion(), valid.name(), valid.lender(), valid.currency(), valid.originalPrincipal(),
                valid.loanStartDate(), valid.firstPaymentDate(), valid.maturityDate(), valid.paymentDay(), valid.debtAllocationShare(),
                valid.balanceTrackingMode(), null, valid.ratePeriod());
        assertThrows(RealEstateServiceImpl.RealEstateException.class, () -> service.createMortgage(PID, p.getId(), missing, "loan"));
        assertEquals("MORTGAGE_SETUP_PENDING", p.getFinancingStatus());
        verify(mortgages, never()).insert(any(Mortgage.class));
    }

    @Test
    void actualPaymentReducesOnlyPrincipalAndReplayIsSafe() {
        Mortgage loan = createLoan();
        PaymentCommand command = new PaymentCommand(loan.getVersion(), TODAY, null,
                new BigDecimal("300"), new BigDecimal("100"), new BigDecimal("600"), new BigDecimal("10"), "Recorded externally", null, null);
        var first = service.payment(PID, loan.getPropertyId(), loan.getId(), null, command, "payment");
        service.payment(PID, loan.getPropertyId(), loan.getId(), null, command, "payment");
        assertEquals(0, new BigDecimal("179600").compareTo(first.attributedMortgagePrincipal()));
        assertEquals(1, loan.getPayments().size());
        verify(mortgages, times(1)).save(any(Mortgage.class));
    }

    @Test
    void paymentRetryTreatsEquivalentDecimalScalesAsTheSameInput() {
        Mortgage loan = createLoan();
        Long originalVersion = loan.getVersion();
        service.payment(PID, loan.getPropertyId(), loan.getId(), null, new PaymentCommand(originalVersion,
                TODAY, null, new BigDecimal("300"), BigDecimal.ZERO, new BigDecimal("600"), BigDecimal.ZERO, null, null, null), "payment-decimals");
        service.payment(PID, loan.getPropertyId(), loan.getId(), null, new PaymentCommand(originalVersion,
                TODAY, null, new BigDecimal("300.00"), new BigDecimal("0.00"), new BigDecimal("600.000"), new BigDecimal("0.0"), null, null, null), "payment-decimals");
        assertEquals(1, loan.getPayments().size());
        verify(mortgages, times(1)).save(any(Mortgage.class));
    }

    @Test
    void payoffPaymentCanBeRecordedAfterItsEndOfDayZeroCheckpoint() {
        Mortgage loan = createLoan();
        service.checkpoint(PID, loan.getPropertyId(), loan.getId(), null,
                new BalanceCommand(loan.getVersion(), TODAY, BigDecimal.ZERO, "BANK_STATEMENT", "Paid off"), "zero-checkpoint");
        var result = service.payment(PID, loan.getPropertyId(), loan.getId(), null,
                new PaymentCommand(loan.getVersion(), TODAY, null, new BigDecimal("180000"), BigDecimal.ZERO,
                        new BigDecimal("600"), BigDecimal.ZERO, "Payoff already included in bank balance", null, null), "payoff-payment");
        assertEquals(0, result.attributedMortgagePrincipal().signum());
        assertEquals(0, new BigDecimal("180000").compareTo(onlySummary(result).recordedPrincipal()));
        var excessive = assertThrows(RealEstateServiceImpl.RealEstateException.class, () -> service.payment(PID,
                loan.getPropertyId(), loan.getId(), null, new PaymentCommand(loan.getVersion(), TODAY, null,
                        BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null), "another-payment"));
        assertEquals(400, excessive.getStatus());
    }

    @Test
    void paymentOnOpeningCheckpointDateIsRecordedWithoutSubtractingItTwice() {
        Mortgage loan = createLoan();
        var openingDate = loan.getBalanceCheckpoints().getFirst().getAsOfDate();
        var result = service.payment(PID, loan.getPropertyId(), loan.getId(), null,
                new PaymentCommand(loan.getVersion(), openingDate, null, new BigDecimal("300"), BigDecimal.ZERO,
                        new BigDecimal("600"), BigDecimal.ZERO, "Already included in opening end-of-day balance", null, null), "opening-day-payment");
        assertEquals(0, new BigDecimal("180000").compareTo(result.attributedMortgagePrincipal()));
        assertEquals(0, new BigDecimal("300").compareTo(onlySummary(result).recordedPrincipal()));
    }

    @Test
    void regularPaymentOnDueDateAutomaticallyReplacesThatDaysEstimate() {
        Mortgage loan = createLoan();
        loan.getBalanceCheckpoints().getFirst().setAsOfDate(LocalDate.of(2026, 9, 1));
        loan.getRatePeriods().getFirst().setEffectiveFrom(LocalDate.of(2026, 9, 1));
        var result = service.payment(PID, loan.getPropertyId(), loan.getId(), null,
                new PaymentCommand(loan.getVersion(), LocalDate.of(2026, 10, 1), null,
                        new BigDecimal("300"), BigDecimal.ZERO, new BigDecimal("600"), BigDecimal.ZERO, null, null, null), "due-date-payment");
        assertEquals(LocalDate.of(2026, 10, 1), loan.getPayments().getFirst().getScheduledDueDate());
        assertEquals(0, new BigDecimal("179700").compareTo(result.attributedMortgagePrincipal()));
    }

    @Test
    void setupCanRecoverAfterReloadLosesTheOriginalRetryKey() {
        Mortgage loan = createLoan();
        RealEstateProperty property = propertyStore.get(loan.getPropertyId());
        property.setFinancingStatus("MORTGAGE_SETUP_PENDING");
        MortgageCommand reopened = mortgageCommand(property, "1");
        var recovered = service.createMortgage(PID, property.getId(), reopened, "new-browser-session-key");
        assertEquals("MORTGAGE_RECORDED", recovered.property().getFinancingStatus());
        assertEquals(1, mortgageStore.size());
        assertTrue(recovered.complete());
        verify(mortgages, times(1)).insert(any(Mortgage.class));
    }

    @Test
    void staleVersionRejectsMutation() {
        Mortgage loan = createLoan();
        var error = assertThrows(RealEstateServiceImpl.RealEstateException.class, () -> service.checkpoint(PID,
                loan.getPropertyId(), loan.getId(), null, new BalanceCommand(99L, TODAY, new BigDecimal("179000"), "BANK_STATEMENT", null), "balance"));
        assertEquals(409, error.getStatus());
        verify(mortgages, never()).save(any(Mortgage.class));
    }

    @Test
    void mortgageFromAnotherParentCannotBeMutated() {
        Mortgage loan = createLoan();
        var another = service.createProperty(PID, propertyCommand("MORTGAGE_FREE", "100000", "1"), "another-property").property();
        var error = assertThrows(RealEstateServiceImpl.RealEstateException.class, () -> service.requireMortgage(PID, another.getId(), loan.getId()));
        assertEquals(404, error.getStatus());
    }

    @Test
    void archiveCannotEraseDebt() {
        Mortgage loan = createLoan();
        RealEstateProperty p = propertyStore.get(loan.getPropertyId());
        var error = assertThrows(RealEstateServiceImpl.RealEstateException.class, () ->
                service.archive(PID, p.getId(), new DateCommand(p.getVersion(), TODAY), "archive"));
        assertEquals(409, error.getStatus());
        assertEquals("ACTIVE", p.getStatus());
    }

    @Test
    void closureRequiresConfirmedZeroOnItsEffectiveDateAndRetainsPastDebt() {
        Mortgage loan = createLoan();
        assertThrows(RealEstateServiceImpl.RealEstateException.class, () -> service.close(PID, loan.getPropertyId(), loan.getId(),
                new DateCommand(loan.getVersion(), TODAY), "close-without-zero"));
        service.checkpoint(PID, loan.getPropertyId(), loan.getId(), null,
                new BalanceCommand(loan.getVersion(), TODAY, BigDecimal.ZERO, "BANK_STATEMENT", "Paid off"), "paid-off");
        service.close(PID, loan.getPropertyId(), loan.getId(), new DateCommand(loan.getVersion(), TODAY), "close");
        assertEquals(TODAY, loan.getCloseDate());
        var old = service.viewFor(propertyStore.get(loan.getPropertyId()), List.of(loan), LocalDate.of(2026, 10, 2), false);
        assertEquals(0, new BigDecimal("180000").compareTo(old.attributedMortgagePrincipal()));
    }

    @Test
    void overlappingRatePeriodIsRejected() {
        Mortgage loan = createLoan();
        var error = assertThrows(RealEstateServiceImpl.RealEstateException.class, () -> service.rate(PID, loan.getPropertyId(), loan.getId(), null,
                new RateCommand(loan.getVersion(), LocalDate.of(2027, 10, 1), LocalDate.of(2030, 9, 30),
                        new BigDecimal("5"), new BigDecimal("1000"), "BANK_AGREED"), "overlap"));
        assertEquals(409, error.getStatus());
    }

    @Test
    void nonAmortizingPaymentIsRejected() {
        var p = service.createProperty(PID, propertyCommand("MORTGAGE_SETUP_PENDING", "300000", "1"), "property").property();
        var valid = mortgageCommand(p, "1");
        var badRate = new RateCommand(null, valid.ratePeriod().effectiveFrom(), valid.ratePeriod().fixedUntil(), new BigDecimal("4"),
                new BigDecimal("600"), "BANK_AGREED");
        var bad = new MortgageCommand(null, p.getVersion(), valid.name(), valid.lender(), valid.currency(), valid.originalPrincipal(),
                valid.loanStartDate(), valid.firstPaymentDate(), valid.maturityDate(), valid.paymentDay(), valid.debtAllocationShare(),
                valid.balanceTrackingMode(), valid.openingBalance(), badRate);
        assertThrows(RealEstateServiceImpl.RealEstateException.class, () -> service.createMortgage(PID, p.getId(), bad, "loan"));
        verify(mortgages, never()).insert(any(Mortgage.class));
    }

    @Test
    void secondActiveLoanAddsItsDebtToTheSameProperty() {
        Mortgage first = createLoan();
        var view = service.createMortgage(PID, first.getPropertyId(), downPaymentCommand(propertyStore.get(first.getPropertyId())), "down-payment");
        assertEquals(2, view.mortgages().stream().filter(m -> "ACTIVE".equals(m.getStatus())).count());
        assertEquals("MORTGAGE_RECORDED", view.property().getFinancingStatus());
        assertEquals(0, new BigDecimal("200000").compareTo(view.attributedMortgagePrincipal()));
        assertEquals(0, new BigDecimal("100000").compareTo(view.equity()));
    }

    @Test
    void pendingPropertyWithAnOpenLoanStillSetsUpADifferentSecondLoan() {
        // Pending with one loan open is the normal state while a second loan is being set up.
        Mortgage first = createLoan();
        propertyStore.get(first.getPropertyId()).setFinancingStatus("MORTGAGE_SETUP_PENDING");
        var view = service.createMortgage(PID, first.getPropertyId(), downPaymentCommand(propertyStore.get(first.getPropertyId())), "down-payment");
        assertEquals(2, view.mortgages().size());
        assertEquals("MORTGAGE_RECORDED", view.property().getFinancingStatus());
    }

    @Test
    void sameLoanEnteredAgainAfterAReloadIsNotDuplicated() {
        Mortgage first = createLoan();
        RealEstateProperty property = propertyStore.get(first.getPropertyId());
        // The insert committed but the parent acknowledgement failed, and a reload lost the key.
        property.setFinancingStatus("MORTGAGE_SETUP_PENDING");
        var view = service.createMortgage(PID, property.getId(), mortgageCommand(property, "1"), "new-key-after-reload");
        assertEquals(1, view.mortgages().size());
        assertEquals("MORTGAGE_RECORDED", view.property().getFinancingStatus());
        // With every loan recorded, the same input again is a duplicate entry, not a retry.
        var error = assertThrows(RealEstateServiceImpl.RealEstateException.class, () ->
                service.createMortgage(PID, property.getId(), mortgageCommand(property, "1"), "third-key"));
        assertEquals(409, error.getStatus());
        assertEquals(1, mortgageStore.size());
    }

    @Test
    void closingOneOfTwoLoansKeepsTheOtherAndArchiveWaitsForBoth() {
        Mortgage first = createLoan();
        String propertyId = first.getPropertyId();
        service.createMortgage(PID, propertyId, downPaymentCommand(propertyStore.get(propertyId)), "down-payment");
        service.checkpoint(PID, propertyId, first.getId(), null,
                new BalanceCommand(first.getVersion(), TODAY, BigDecimal.ZERO, "BANK_STATEMENT", "Paid off"), "paid-off");
        var view = service.close(PID, propertyId, first.getId(), new DateCommand(first.getVersion(), TODAY), "close");
        assertEquals("MORTGAGE_RECORDED", view.property().getFinancingStatus());
        assertEquals(0, new BigDecimal("20000").compareTo(view.attributedMortgagePrincipal()));
        var error = assertThrows(RealEstateServiceImpl.RealEstateException.class, () ->
                service.archive(PID, propertyId, new DateCommand(propertyStore.get(propertyId).getVersion(), TODAY), "archive"));
        assertEquals(409, error.getStatus());
    }

    @Test
    void deletingOneOfTwoOpenLoansCannotMakeThePropertyMortgageFree() {
        Mortgage first = createLoan();
        String propertyId = first.getPropertyId();
        service.createMortgage(PID, propertyId, downPaymentCommand(propertyStore.get(propertyId)), "down-payment");
        var refused = assertThrows(RealEstateServiceImpl.RealEstateException.class, () ->
                service.deleteMortgage(PID, propertyId, first.getId(), first.getVersion(), "MORTGAGE_FREE"));
        assertEquals(400, refused.getStatus());
        var view = service.deleteMortgage(PID, propertyId, first.getId(), first.getVersion(), "MORTGAGE_RECORDED");
        assertEquals(1, view.mortgages().size());
        assertEquals("MORTGAGE_RECORDED", view.property().getFinancingStatus());
        assertEquals(0, new BigDecimal("280000").compareTo(view.equity()));
    }

    @Test
    void deletingOneOfTwoForACorrectedLoanLeavesEquityUnknown() {
        Mortgage first = createLoan();
        String propertyId = first.getPropertyId();
        service.createMortgage(PID, propertyId, downPaymentCommand(propertyStore.get(propertyId)), "down-payment");
        var view = service.deleteMortgage(PID, propertyId, first.getId(), first.getVersion(), "MORTGAGE_SETUP_PENDING");
        assertEquals("MORTGAGE_SETUP_PENDING", view.property().getFinancingStatus());
        assertNull(view.equity());
    }

    @Test
    void editingKeepsAPendingDeclarationUntilTheCallerConfirmsEveryLoan() {
        Mortgage first = createLoan();
        RealEstateProperty property = propertyStore.get(first.getPropertyId());
        property.setFinancingStatus("MORTGAGE_SETUP_PENDING");
        var renamed = service.updateProperty(PID, property.getId(), editCommand(property, "MORTGAGE_SETUP_PENDING"), "rename");
        assertEquals("MORTGAGE_SETUP_PENDING", renamed.property().getFinancingStatus());
        var confirmed = service.updateProperty(PID, property.getId(), editCommand(property, "MORTGAGE_RECORDED"), "all-recorded");
        assertEquals("MORTGAGE_RECORDED", confirmed.property().getFinancingStatus());

        var bare = service.createProperty(PID, propertyCommand("MORTGAGE_SETUP_PENDING", "200000", "1"), "bare").property();
        var error = assertThrows(RealEstateServiceImpl.RealEstateException.class, () ->
                service.updateProperty(PID, bare.getId(), editCommand(bare, "MORTGAGE_RECORDED"), "no-loan"));
        assertEquals(400, error.getStatus());
    }

    @Test
    void loweringTheInstallmentNeedsExtraPrincipalAndAnAmortizingAmount() {
        Mortgage loan = createLoan();
        var noExtra = assertThrows(RealEstateServiceImpl.RealEstateException.class, () -> service.payment(PID, loan.getPropertyId(), loan.getId(), null,
                new PaymentCommand(loan.getVersion(), TODAY, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, true, null), "no-extra"));
        assertEquals(400, noExtra.getStatus());
        // 170,000 left at 4% carries 566.67 interest a month: 100 cannot repay it.
        var tooLow = assertThrows(RealEstateServiceImpl.RealEstateException.class, () -> service.payment(PID, loan.getPropertyId(), loan.getId(), null,
                new PaymentCommand(loan.getVersion(), TODAY, null, BigDecimal.ZERO, new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, null, true, new BigDecimal("100")), "too-low"));
        assertEquals(400, tooLow.getStatus());
        var view = service.payment(PID, loan.getPropertyId(), loan.getId(), null,
                new PaymentCommand(loan.getVersion(), TODAY, null, BigDecimal.ZERO, new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, null, true, null), "lower");
        assertTrue(loan.getPayments().getFirst().getLowerInstallment());
        assertNull(loan.getPayments().getFirst().getNewInstallment());
        // 170,000 over the 336 installments still due (Nov 2026 to Oct 2054), down from 891.38 on 180,000.
        BigDecimal recast = new MortgageCalculationService(service.clock).monthlyPayment(new BigDecimal("170000"), new BigDecimal("4"), 336);
        assertEquals(0, recast.compareTo(onlySummary(view).monthlyPayment()), "Recast installment " + onlySummary(view).monthlyPayment());
        assertEquals(336, onlySummary(view).remainingPaymentCount(), "Lowering the installment keeps the end date");
    }

    @Test
    void loweringTheInstallmentReworksALaterCalculatedRate() {
        Mortgage loan = createLoan();
        LocalDate refix = LocalDate.of(2029, 10, 1);
        service.rate(PID, loan.getPropertyId(), loan.getId(), null,
                new RateCommand(loan.getVersion(), refix, LocalDate.of(2034, 9, 30), new BigDecimal("5"), null, null), "next-rate");
        Mortgage.RatePeriod next = loan.getRatePeriods().stream().filter(p -> refix.equals(p.getEffectiveFrom())).findFirst().orElseThrow();
        service.payment(PID, loan.getPropertyId(), loan.getId(), null,
                new PaymentCommand(loan.getVersion(), TODAY, null, BigDecimal.ZERO, new BigDecimal("20000"), BigDecimal.ZERO, BigDecimal.ZERO, null, true, null), "lower");
        Mortgage.RatePeriod reworked = loan.getRatePeriods().stream().filter(p -> refix.equals(p.getEffectiveFrom())).findFirst().orElseThrow();
        // Worked out on the balance the extra payment left, as saving that rate now would.
        var calc = new MortgageCalculationService(service.clock);
        BigDecimal expected = calc.monthlyPayment(calc.balanceAt(loan, refix).principalBalance(), new BigDecimal("5"),
                calc.remainingPaymentCount(loan, refix), 2);
        assertEquals(0, expected.compareTo(reworked.getMonthlyPayment()), "Reworked installment " + reworked.getMonthlyPayment());
        assertTrue(reworked.getMonthlyPayment().compareTo(next.getMonthlyPayment()) < 0);
        assertEquals(next.getId(), reworked.getId());
        assertEquals(next.getRecordedAt(), reworked.getRecordedAt());
    }

    @Test
    void anExtraPaymentThatKeepsTheInstallmentLeavesLaterRatesAlone() {
        Mortgage other = createLoan();
        LocalDate refix = LocalDate.of(2029, 10, 1);
        service.rate(PID, other.getPropertyId(), other.getId(), null,
                new RateCommand(other.getVersion(), refix, LocalDate.of(2034, 9, 30), new BigDecimal("5"), null, null), "next-rate");
        BigDecimal kept = other.getRatePeriods().stream().filter(p -> refix.equals(p.getEffectiveFrom())).findFirst().orElseThrow().getMonthlyPayment();
        service.payment(PID, other.getPropertyId(), other.getId(), null,
                new PaymentCommand(other.getVersion(), TODAY, null, BigDecimal.ZERO, new BigDecimal("20000"), BigDecimal.ZERO, BigDecimal.ZERO, null, null, null), "keep");
        assertEquals(kept, other.getRatePeriods().stream().filter(p -> refix.equals(p.getEffectiveFrom())).findFirst().orElseThrow().getMonthlyPayment());
    }

    @Test
    void previewOfALowerInstallmentKeepsTheEndDateAcrossARecordedRefixation() {
        Mortgage loan = createLoan();
        service.rate(PID, loan.getPropertyId(), loan.getId(), null,
                new RateCommand(loan.getVersion(), LocalDate.of(2029, 10, 1), LocalDate.of(2054, 10, 1), new BigDecimal("5"), null, null), "to-maturity");
        var preview = service.extraPaymentPreview(PID, loan.getPropertyId(), loan.getId(), new ExtraPaymentCommand(LocalDate.of(2027, 3, 15), new BigDecimal("20000")));
        assertNull(preview.assumedRatePercent(), "Rates are agreed to maturity: nothing is assumed");
        assertEquals(LocalDate.of(2054, 10, 1), preview.current().payoffDate());
        assertEquals(LocalDate.of(2054, 10, 1), preview.lowerInstallment().payoffDate());
        assertTrue(preview.keepInstallment().payoffDate().isBefore(LocalDate.of(2054, 10, 1)));
    }

    @Test
    void extraPaymentPreviewComparesKeepingAndLoweringTheInstallmentWithoutSaving() {
        Mortgage loan = createLoan();
        var preview = service.extraPaymentPreview(PID, loan.getPropertyId(), loan.getId(), new ExtraPaymentCommand(TODAY, new BigDecimal("20000")));
        // The fixation ends 30 Sep 2029, long before maturity: the 4% is carried to 2054, and said so.
        assertEquals(0, new BigDecimal("4").compareTo(preview.assumedRatePercent()));
        assertEquals(LocalDate.of(2029, 9, 30), preview.assumedAfter());
        assertEquals(0, preview.current().installment().compareTo(preview.keepInstallment().installment()));
        assertEquals(LocalDate.of(2054, 10, 1), preview.current().payoffDate());
        assertTrue(preview.keepInstallment().payoffDate().isBefore(preview.current().payoffDate()), "Keeping the installment ends the loan sooner");
        assertEquals(preview.current().payoffDate(), preview.lowerInstallment().payoffDate(), "Lowering it keeps the end date");
        assertTrue(preview.lowerInstallment().installment().compareTo(preview.current().installment()) < 0);
        assertTrue(preview.keepInstallment().remainingInterest().compareTo(preview.lowerInstallment().remainingInterest()) < 0);
        assertTrue(preview.lowerInstallment().remainingInterest().compareTo(preview.current().remainingInterest()) < 0);
        assertTrue(loan.getPayments().isEmpty(), "A preview saves nothing");
        var tooMuch = assertThrows(RealEstateServiceImpl.RealEstateException.class, () ->
                service.extraPaymentPreview(PID, loan.getPropertyId(), loan.getId(), new ExtraPaymentCommand(TODAY, new BigDecimal("500000"))));
        assertEquals(400, tooMuch.getStatus());
    }

    @Test
    void debtStatusSumsTheInstallmentsOfEveryOpenLoan() {
        Mortgage first = createLoan();
        String propertyId = first.getPropertyId();
        var view = service.createMortgage(PID, propertyId, downPaymentCommand(propertyStore.get(propertyId)), "down-payment");
        var debt = view.debt();
        assertEquals("CARRIED", debt.provenance());
        assertEquals(LocalDate.of(2026, 11, 1), debt.nextDueDate());
        BigDecimal sum = view.loanSummaries().values().stream()
                .map(MortgageCalculationService.Summary::attributedMonthlyPayment).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(debt.attributedMonthlyPayment()));
        assertTrue(debt.loansNeedingAttention().isEmpty());
    }

    @Test
    void oneLoanNeedingARateMakesThePropertyDebtPausedAndNamesIt() {
        Mortgage first = createLoan();
        String propertyId = first.getPropertyId();
        var view = service.createMortgage(PID, propertyId, downPaymentCommand(propertyStore.get(propertyId), LocalDate.of(2026, 10, 15)), "down-payment");
        var debt = view.debt();
        assertEquals("PAUSED", debt.provenance());
        assertTrue(debt.rateNeeded());
        assertNull(debt.attributedMonthlyPayment());
        String downPayment = view.mortgages().stream().filter(m -> "Down-payment loan".equals(m.getName())).findFirst().orElseThrow().getId();
        assertEquals(List.of(downPayment), debt.loansNeedingAttention());
        assertTrue(debt.warnings().stream().anyMatch(w -> w.startsWith("Down-payment loan: ")));
    }

    private Mortgage createLoan() {
        var p = service.createProperty(PID, propertyCommand("MORTGAGE_SETUP_PENDING", "300000", "1"), "property").property();
        return service.createMortgage(PID, p.getId(), mortgageCommand(p, "1"), "loan").mortgages().getFirst();
    }

    private PropertyCommand propertyCommand(String financing, String value, String ownership) {
        return new PropertyCommand(null, "Fictional apartment", "APARTMENT", "HOME", "EUR", "Slovakia", null,
                null, null, null, new BigDecimal(ownership), LocalDate.of(2026, 10, 1), financing, null,
                LocalDate.of(2026, 10, 1), new BigDecimal(value), "OWNER_ESTIMATE");
    }

    /** A loan that paid the down payment, beside the main mortgage: nothing due before TODAY. */
    private MortgageCommand downPaymentCommand(RealEstateProperty property) {
        return downPaymentCommand(property, LocalDate.of(2036, 10, 1));
    }

    private MortgageCommand downPaymentCommand(RealEstateProperty property, LocalDate fixedUntil) {
        return new MortgageCommand(null, property.getVersion(), "Down-payment loan", "Fictional bank", "EUR", new BigDecimal("20000"),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), LocalDate.of(2036, 10, 1), 1, BigDecimal.ONE, "SCHEDULE_ESTIMATE",
                new BalanceCommand(null, LocalDate.of(2026, 10, 1), new BigDecimal("20000"), "BANK_STATEMENT", null),
                new RateCommand(null, LocalDate.of(2026, 10, 1), fixedUntil, new BigDecimal("7"), null, null));
    }

    /** The one loan of a single-loan property. */
    private static MortgageCalculationService.Summary onlySummary(RealEstateServiceImpl.PropertyView view) {
        assertEquals(1, view.loanSummaries().size());
        return view.loanSummaries().values().iterator().next();
    }

    private PropertyCommand editCommand(RealEstateProperty p, String financing) {
        return new PropertyCommand(p.getVersion(), "Renamed apartment", p.getPropertyType(), p.getUsage(), p.getCurrency(),
                p.getCountry(), p.getLocation(), p.getPurchaseDate(), p.getPurchasePrice(), p.getAcquisitionCosts(),
                p.getOwnershipShare(), p.getTrackingStartDate(), financing, p.getNotes(), null, null, null);
    }

    private MortgageCommand mortgageCommand(RealEstateProperty property, String allocation) {
        return new MortgageCommand(null, property.getVersion(), "Home loan", "Fictional bank", "EUR", new BigDecimal("200000"),
                LocalDate.of(2024, 10, 1), LocalDate.of(2024, 11, 1), LocalDate.of(2054, 10, 1), 1,
                new BigDecimal(allocation), "SCHEDULE_ESTIMATE",
                new BalanceCommand(null, LocalDate.of(2026, 10, 1), new BigDecimal("180000"), "BANK_STATEMENT", null),
                new RateCommand(null, LocalDate.of(2026, 10, 1), LocalDate.of(2029, 9, 30), new BigDecimal("4"), null, null));
    }
}
