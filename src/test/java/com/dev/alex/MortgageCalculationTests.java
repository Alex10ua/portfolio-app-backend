package com.dev.alex;

import com.dev.alex.Model.Mortgage;
import com.dev.alex.Service.MortgageCalculationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic loan fixtures; no database, network, or user records. */
class MortgageCalculationTests {
    private final MortgageCalculationService engine = new MortgageCalculationService(
            Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void annuityAndZeroInterestUseDecimalMoney() {
        amount("891.38", engine.monthlyPayment(bd("180000"), bd("4"), 336));
        amount("100.00", engine.monthlyPayment(bd("1200"), BigDecimal.ZERO, 12));
        assertThrows(IllegalArgumentException.class, () -> engine.monthlyPayment(bd("10"), bd("-1"), 12));
        assertThrows(IllegalArgumentException.class, () -> engine.monthlyPayment(bd("10"), bd("1"), 0));
    }

    @Test
    void scheduledPrincipalAndInterestStaySeparateAndFutureIsProjected() {
        Mortgage m = mortgage("2026-10-01", "180000", "2026-11-01", "2054-10-01", 1, "4", "891.38");
        List<MortgageCalculationService.Installment> rows = engine.schedule(m, day("2026-10-01"), day("2026-11-01"), day("2026-10-27"));
        var due = rows.stream().filter(r -> "INSTALLMENT".equals(r.kind())).findFirst().orElseThrow();
        amount("600.00", due.interest());
        amount("291.38", due.principal());
        amount("179708.62", due.balance());
        assertEquals("PROJECTED", due.provenance());
        amount("180000", engine.balanceAt(m, day("2026-10-27")).principalBalance());
    }

    @Test
    void actualInstallmentReplacesScheduledEstimateIncludingLatePayment() {
        Mortgage m = mortgage("2026-01-01", "1200", "2026-02-01", "2027-01-01", 1, "0", "100");
        m.getPayments().add(payment("2026-02-03", "2026-02-01", "80", "20", "7", "3"));
        amount("1100", engine.balanceAt(m, day("2026-02-03")).principalBalance());
        amount("1000", engine.balanceAt(m, day("2026-03-01")).principalBalance());
        var summary = engine.summarize(m, day("2026-02-03"));
        amount("100", summary.recordedPrincipal());
        amount("7", summary.recordedInterest());
        amount("3", summary.recordedFees());
    }

    @Test
    void earlyInstallmentIncludedInOpeningBankBalanceIsNotDeductedAgainAtItsDueDate() {
        Mortgage m = mortgage("2026-02-15", "1100", "2026-03-01", "2027-02-01", 1, "0", "100");
        m.setLoanStartDate(day("2026-01-01"));
        m.getPayments().add(payment("2026-02-10", "2026-03-01", "100", "0", "0", "0"));
        amount("1100", engine.balanceAt(m, day("2026-03-01")).principalBalance());
        amount("1000", engine.balanceAt(m, day("2026-04-01")).principalBalance());
    }

    @Test
    void checkpointIncludesSameDayActivityAndResetsEstimates() {
        Mortgage m = mortgage("2026-01-01", "1200", "2026-02-01", "2027-01-01", 1, "0", "100");
        m.getPayments().add(payment("2026-02-01", "2026-02-01", "100", "0", "0", "0"));
        m.getBalanceCheckpoints().add(checkpoint("2026-02-01", "1050"));
        var balance = engine.balanceAt(m, day("2026-02-01"));
        amount("1050", balance.principalBalance());
        assertEquals("CONFIRMED", balance.provenance());
        amount("950", engine.balanceAt(m, day("2026-03-01")).principalBalance());
        amount("1200", engine.balanceAt(m, day("2026-01-31")).principalBalance());
    }

    @Test
    void statementOnlyDoesNotAssumeAPassedDueWasPaid() {
        Mortgage m = mortgage("2026-01-01", "1200", "2026-02-01", "2027-01-01", 1, "0", "100");
        m.setBalanceTrackingMode("STATEMENT_ONLY");
        var balance = engine.balanceAt(m, day("2026-03-10"));
        amount("1200", balance.principalBalance());
        assertEquals("CARRIED", balance.provenance());
        m.getPayments().add(payment("2026-03-01", "2026-03-01", "100", "0", "0", "0"));
        amount("1100", engine.balanceAt(m, day("2026-03-10")).principalBalance());
    }

    @Test
    void missedInstallmentSuppressesEstimateAndExtraPrincipalKeepsInstallment() {
        Mortgage m = mortgage("2026-01-01", "1200", "2026-02-01", "2027-01-01", 1, "0", "100");
        m.getPayments().add(payment("2026-02-01", "2026-02-01", "0", "0", "0", "0"));
        m.getPayments().add(payment("2026-02-10", null, "0", "300", "0", "0"));
        amount("900", engine.balanceAt(m, day("2026-02-10")).principalBalance());
        amount("800", engine.balanceAt(m, day("2026-03-01")).principalBalance());
        amount("100", engine.summarize(m, day("2026-03-01")).monthlyPayment());
        assertEquals(day("2026-11-01"), engine.summarize(m, day("2026-03-01")).projectedPayoffDate());
    }

    @Test
    void rateExpiryPausesInferenceButAllowsANewerBankCheckpoint() {
        Mortgage m = mortgage("2026-01-01", "1200", "2026-02-01", "2027-01-01", 1, "0", "100");
        m.getRatePeriods().getFirst().setFixedUntil(day("2026-02-28"));
        amount("1000", engine.balanceAt(m, day("2026-03-01")).principalBalance());
        var paused = engine.balanceAt(m, day("2026-04-01"));
        amount("1000", paused.principalBalance());
        assertTrue(paused.rateNeeded());
        assertEquals("PAUSED", paused.provenance());
        m.getBalanceCheckpoints().add(checkpoint("2026-04-10", "940"));
        var fresh = engine.balanceAt(m, day("2026-04-10"));
        amount("940", fresh.principalBalance());
        assertEquals("CONFIRMED", fresh.provenance());
        assertTrue(fresh.rateNeeded());
    }

    @Test
    void rateResetUsesRemainingTermAndDoesNotAlterPreviousHistory() {
        Mortgage m = mortgage("2026-01-01", "1200", "2026-02-01", "2027-01-01", 1, "0", "100");
        m.getRatePeriods().getFirst().setFixedUntil(day("2026-03-31"));
        Mortgage.RatePeriod next = rate("2026-04-01", "2027-01-31", "12", "120");
        m.getRatePeriods().add(next);
        m.getPayments().add(payment("2026-04-01", "2026-04-01", "100", "0", "0", "0"));
        amount("1000", engine.balanceAt(m, day("2026-03-01")).principalBalance());
        amount("900", engine.balanceAt(m, day("2026-04-01")).principalBalance());
        amount("789", engine.balanceAt(m, day("2026-05-01")).principalBalance());
        assertEquals(8, engine.remainingPaymentCount(m, day("2026-05-01")));
        var actualAtReset = engine.schedule(m, day("2026-04-01"), day("2026-04-01"), day("2026-10-27"))
                .stream().filter(p -> "PAYMENT".equals(p.kind())).findFirst().orElseThrow();
        amount("0", actualAtReset.annualNominalRatePercent());
    }

    @Test
    void knownMidCycleRateResetNeedsBankFiguresRatherThanAnotherRate() {
        Mortgage m = mortgage("2026-01-01", "1200", "2026-02-01", "2027-01-01", 1, "0", "100");
        m.getRatePeriods().getFirst().setFixedUntil(day("2026-02-14"));
        m.getRatePeriods().add(rate("2026-02-15", "2027-01-31", "12", "110"));
        var beforeDue = engine.summarize(m, day("2026-02-20"));
        assertTrue(beforeDue.balance().partialPeriodUnsupported());
        assertFalse(beforeDue.balance().rateNeeded());
        amount("110", beforeDue.monthlyPayment());
        var paused = engine.balanceAt(m, day("2026-03-01"));
        amount("1100", paused.principalBalance());
        assertEquals("PAUSED", paused.provenance());
        assertTrue(paused.partialPeriodUnsupported());
        assertFalse(paused.rateNeeded());
        assertTrue(engine.schedule(m, day("2026-03-01"), day("2026-03-01"), day("2026-10-27"))
                .stream().anyMatch(p -> "UNSUPPORTED_PERIOD".equals(p.kind())));

        m.getPayments().add(payment("2026-03-01", "2026-03-01", "90", "0", "20", "0"));
        amount("1010", engine.balanceAt(m, day("2026-03-01")).principalBalance());
        amount("910.10", engine.balanceAt(m, day("2026-04-01")).principalBalance());
    }

    @Test
    void paymentDayReturnsToThirtyOneAfterLeapFebruary() {
        Mortgage m = mortgage("2024-01-31", "1200", "2024-02-29", "2025-01-31", 31, "0", "100");
        assertEquals(List.of(day("2024-02-29"), day("2024-03-31"), day("2024-04-30")), engine.dueDates(m).subList(0, 3));
        amount("1000", engine.balanceAt(m, day("2024-03-31")).principalBalance());
    }

    @Test
    void payoutInsideACycleAccruesFromThePayoutAndKeepsEstimating() {
        // Paid out on 15 Jan, first installment 1 Feb: 17 of January's 31 days carry interest.
        Mortgage m = mortgage("2026-01-15", "1200", "2026-02-01", "2027-01-01", 1, "12", "110");
        var first = engine.balanceAt(m, day("2026-02-01"));
        amount("1096.58", first.principalBalance());
        assertEquals("ESTIMATED", first.provenance());
        assertFalse(first.partialPeriodUnsupported());
        var stub = engine.schedule(m, day("2026-02-01"), day("2026-02-01"), day("2026-10-27")).stream()
                .filter(r -> "INSTALLMENT".equals(r.kind())).findFirst().orElseThrow();
        amount("6.58", stub.interest());
        amount("103.42", stub.principal());
        amount("997.55", engine.balanceAt(m, day("2026-03-01")).principalBalance());

        m.getRatePeriods().getFirst().setMonthlyPayment(null);
        var beforeFirstDue = engine.summarize(m, day("2026-01-20"));
        assertFalse(beforeFirstDue.balance().partialPeriodUnsupported());
        amount("106.62", beforeFirstDue.monthlyPayment());
    }

    @Test
    void aBalanceReadBetweenInstallmentsStandsForTheWholeCycle() {
        // An existing loan opened from a statement read on 20 Jan: nothing moves principal before 1 Feb.
        Mortgage m = mortgage("2026-01-20", "1200", "2025-02-01", "2027-01-01", 1, "12", "110");
        m.setLoanStartDate(day("2025-01-01"));
        assertFalse(engine.summarize(m, day("2026-01-25")).balance().partialPeriodUnsupported());
        var afterDue = engine.balanceAt(m, day("2026-02-01"));
        amount("1102", afterDue.principalBalance());
        assertEquals("ESTIMATED", afterDue.provenance());
        // A later reconciliation read mid-month carries on the same way.
        m.getBalanceCheckpoints().add(checkpoint("2026-02-10", "1090"));
        var reconciled = engine.balanceAt(m, day("2026-03-01"));
        amount("990.90", reconciled.principalBalance());
        assertFalse(reconciled.partialPeriodUnsupported());
    }

    @Test
    void extraPaymentThatLowersTheInstallmentKeepsTheEndDate() {
        // 1,200 at 12% (1% a month) over 12 installments of 106.62.
        Mortgage keep = mortgage("2026-01-01", "1200", "2026-02-01", "2027-01-01", 1, "12", "106.62");
        Mortgage lower = mortgage("2026-01-01", "1200", "2026-02-01", "2027-01-01", 1, "12", "106.62");
        keep.getPayments().add(payment("2026-02-10", null, "0", "300", "0", "0"));
        Mortgage.Payment extra = payment("2026-02-10", null, "0", "300", "0", "0");
        extra.setLowerInstallment(true);
        lower.getPayments().add(extra);
        // The 1 Feb installment pays 12.00 interest and 94.62 principal: 1,105.38 left, 805.38 after the extra 300.
        BigDecimal expected = engine.monthlyPayment(bd("805.38"), bd("12"), 11);
        var lowered = engine.summarize(lower, day("2026-02-10"));
        amount(expected.toPlainString(), lowered.monthlyPayment());
        assertEquals(day("2027-01-01"), lowered.projectedPayoffDate(), "Lowering the installment keeps the end date");
        var kept = engine.summarize(keep, day("2026-02-10"));
        amount("106.62", kept.monthlyPayment());
        assertTrue(kept.projectedPayoffDate().isBefore(day("2027-01-01")), "Keeping it ends the loan sooner");
        var row = engine.schedule(lower, day("2026-02-10"), day("2026-02-10"), day("2026-10-27")).stream()
                .filter(r -> "PAYMENT".equals(r.kind())).findFirst().orElseThrow();
        assertEquals("Installment lowered to " + expected.toPlainString(), row.note());
    }

    @Test
    void theBanksNewInstallmentWinsOverTheCalculatedOne() {
        Mortgage m = mortgage("2026-01-01", "1200", "2026-02-01", "2027-01-01", 1, "12", "106.62");
        Mortgage.Payment extra = payment("2026-02-10", null, "0", "300", "0", "0");
        extra.setLowerInstallment(true); extra.setNewInstallment(bd("80"));
        m.getPayments().add(extra);
        amount("80", engine.summarize(m, day("2026-02-10")).monthlyPayment());
        // 1 Mar: 8.05 interest on 805.38, so 71.95 of the 80 is principal.
        amount("733.43", engine.balanceAt(m, day("2026-03-01")).principalBalance());
    }

    @Test
    void nonAmortizingInstallmentIsExplicitlyUnsupported() {
        Mortgage interestOnly = mortgage("2026-01-01", "1200", "2026-02-01", "2027-01-01", 1, "12", "12");
        var blocked = engine.balanceAt(interestOnly, day("2026-02-01"));
        amount("1200", blocked.principalBalance());
        assertEquals("PAUSED", blocked.provenance());
        assertTrue(blocked.warnings().stream().anyMatch(w -> w.contains("does not amortize")));
    }

    @Test
    void closureAndFutureRecordsDoNotRewriteEarlierDebt() {
        Mortgage m = mortgage("2026-01-01", "1200", "2026-02-01", "2027-01-01", 1, "0", "100");
        m.getBalanceCheckpoints().add(checkpoint("2026-05-01", "0"));
        m.setStatus("CLOSED"); m.setCloseDate(day("2026-05-01"));
        amount("1000", engine.balanceAt(m, day("2026-03-01")).principalBalance());
        amount("0", engine.balanceAt(m, day("2026-05-01")).principalBalance());
        assertNull(engine.summarize(m, day("2026-06-01")).nextDueDate());
        assertNull(engine.balanceAt(m, day("2025-12-31")).principalBalance());
        m.getBalanceCheckpoints().add(checkpoint("2026-12-01", "500"));
        amount("0", engine.balanceAt(m, day("2026-10-27")).principalBalance());
    }

    @Test
    void lastInstallmentOnlyAbsorbsSmallRoundingRemainder() {
        Mortgage normal = mortgage("2026-01-01", "1000", "2026-02-01", "2026-04-01", 1, "0", "333.33");
        amount("0", engine.balanceAt(normal, day("2026-04-01")).principalBalance());
        Mortgage balloon = mortgage("2026-01-01", "1000", "2026-02-01", "2026-04-01", 1, "0", "100");
        var result = engine.balanceAt(balloon, day("2026-04-01"));
        amount("700", result.principalBalance());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("maturity")));
    }

    @Test
    void futureOverpaymentIsIgnoredByOrdinaryHistoryButWorksInAnExplicitScenario() {
        Mortgage m = mortgage("2026-10-01", "1200", "2026-11-01", "2027-10-01", 1, "0", "100");
        m.getPayments().add(payment("2026-11-01", null, "0", "300", "0", "0"));
        amount("1100", engine.balanceAt(m, day("2026-11-01")).principalBalance());
        amount("800", engine.summarizeScenario(m, day("2026-11-01")).balance().principalBalance());
        var future = engine.scheduleScenario(m, day("2026-11-01"), day("2026-11-01"), day("2026-10-27"));
        assertEquals("PROJECTED", future.stream().filter(r -> "PAYMENT".equals(r.kind())).findFirst().orElseThrow().provenance());
        amount("0", engine.summarizeScenario(m, day("2026-11-01")).recordedPrincipal());
    }

    private Mortgage mortgage(String anchor, String principal, String firstDue, String maturity, int day, String pct, String payment) {
        Mortgage m = new Mortgage();
        m.setId("synthetic-loan"); m.setCurrency("EUR"); m.setLoanStartDate(day(anchor));
        m.setFirstPaymentDate(day(firstDue)); m.setMaturityDate(day(maturity)); m.setPaymentDay(day);
        m.setDebtAllocationShare(BigDecimal.ONE); m.setBalanceTrackingMode("SCHEDULE_ESTIMATE"); m.setStatus("ACTIVE");
        m.setBalanceCheckpoints(new ArrayList<>(List.of(checkpoint(anchor, principal))));
        m.setRatePeriods(new ArrayList<>(List.of(rate(anchor, "2055-12-31", pct, payment))));
        return m;
    }

    private Mortgage.RatePeriod rate(String from, String until, String pct, String payment) {
        Mortgage.RatePeriod r = new Mortgage.RatePeriod();
        r.setId(from); r.setEffectiveFrom(day(from)); r.setFixedUntil(day(until));
        r.setAnnualNominalRatePercent(bd(pct)); r.setMonthlyPayment(bd(payment)); r.setPaymentSource("BANK_AGREED");
        return r;
    }

    private Mortgage.BalanceCheckpoint checkpoint(String date, String principal) {
        Mortgage.BalanceCheckpoint c = new Mortgage.BalanceCheckpoint();
        c.setId(date); c.setAsOfDate(day(date)); c.setPrincipalBalance(bd(principal)); c.setSource("BANK_STATEMENT");
        return c;
    }

    private Mortgage.Payment payment(String date, String due, String principal, String extra, String interest, String fees) {
        Mortgage.Payment p = new Mortgage.Payment(); p.setId(date);
        p.setPaymentDate(day(date)); p.setScheduledDueDate(due == null ? null : day(due));
        p.setPrincipalPaid(bd(principal)); p.setExtraPrincipalPaid(bd(extra));
        p.setInterestPaid(bd(interest)); p.setFeesPaid(bd(fees));
        return p;
    }

    private static LocalDate day(String date) { return LocalDate.parse(date); }
    private static BigDecimal bd(String amount) { return new BigDecimal(amount); }
    private static void amount(String expected, BigDecimal actual) {
        assertNotNull(actual); assertEquals(0, bd(expected).compareTo(actual), "Expected " + expected + " but got " + actual);
    }
}
