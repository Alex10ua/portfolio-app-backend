package com.dev.alex.Service;

import com.dev.alex.Model.Mortgage;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Principal is reconstructed from dated records, never from a mutable current-balance field.
 * The monthly rate/12 convention pauses at unknown rates and at a rate change inside one cycle:
 * a later bank checkpoint can restore a usable balance without inventing past payments.
 * Principal moves only on installments and recorded payments, so a balance read between
 * installments stands for its whole cycle; a loan paid out inside a cycle accrues from the payout.
 */
@Service
public class MortgageCalculationService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TWELVE_HUNDRED = new BigDecimal("1200");
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int MAX_PAYMENTS = 1200;
    private static final String SPLIT_RATE_WARNING =
            "The rate changes inside a monthly cycle; record the bank's figures for that installment or a later bank balance";
    private final Clock clock;

    public MortgageCalculationService() {
        this(Clock.systemDefaultZone());
    }

    public MortgageCalculationService(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public record Balance(BigDecimal principalBalance, String provenance, LocalDate asOfDate,
                          LocalDate lastConfirmedDate, boolean rateNeeded,
                          boolean partialPeriodUnsupported, List<String> warnings) {}

    public record Summary(Balance balance, LocalDate nextDueDate, BigDecimal monthlyPayment,
                          BigDecimal attributedMonthlyPayment, Mortgage.RatePeriod currentRatePeriod,
                          Mortgage.RatePeriod lastRatePeriod, int remainingPaymentCount,
                          BigDecimal recordedPrincipal, BigDecimal recordedInterest, BigDecimal recordedFees,
                          LocalDate projectedPayoffDate) {}

    public record Installment(String kind, LocalDate date, LocalDate scheduledDueDate,
                              BigDecimal principal, BigDecimal extraPrincipal, BigDecimal interest,
                              BigDecimal fees, BigDecimal total, BigDecimal balance, String provenance,
                              BigDecimal annualNominalRatePercent, String note) {}

    /** `installments`: what each rate period ended up paying, by period, including a lowered one. */
    private record Replay(Balance balance, List<Installment> rows, Map<String, BigDecimal> installments) {}

    public BigDecimal monthlyPayment(BigDecimal principal, BigDecimal annualPercent, int paymentCount) {
        return monthlyPayment(principal, annualPercent, paymentCount, 2);
    }

    public BigDecimal monthlyPayment(BigDecimal principal, BigDecimal annualPercent, int paymentCount, int scale) {
        if (principal == null || annualPercent == null || principal.signum() < 0 || annualPercent.signum() < 0
                || paymentCount < 1 || paymentCount > MAX_PAYMENTS || scale < 0 || scale > 6) {
            throw new IllegalArgumentException("Valid principal, nonnegative interest rate and remaining payments are required");
        }
        if (principal.signum() == 0) return ZERO.setScale(scale);
        BigDecimal rate = annualPercent.divide(TWELVE_HUNDRED, MC);
        if (rate.signum() == 0) return principal.divide(BigDecimal.valueOf(paymentCount), scale, RoundingMode.HALF_UP);
        BigDecimal growth = ONE.add(rate, MC).pow(paymentCount, MC);
        BigDecimal denominator = ONE.subtract(ONE.divide(growth, MC), MC);
        return principal.multiply(rate, MC).divide(denominator, MC).setScale(scale, RoundingMode.HALF_UP);
    }

    /** Count scheduled dues strictly after the anchor; maturity is inclusive. */
    public int remainingPaymentCount(Mortgage mortgage, LocalDate afterDate) {
        return (int) dueDates(mortgage).stream().filter(d -> d.isAfter(afterDate)).count();
    }

    public Balance balanceAt(Mortgage mortgage, LocalDate asOfDate) {
        return replay(mortgage, asOfDate, earlier(asOfDate, LocalDate.now(clock))).balance();
    }

    public Summary summarize(Mortgage mortgage, LocalDate asOfDate) {
        return summarizeInternal(mortgage, asOfDate, false, true);
    }

    /** Historical aggregation needs confidence/payment metadata, not a repeated lifetime projection. */
    public Summary summarize(Mortgage mortgage, LocalDate asOfDate, boolean includeProjection) {
        return summarizeInternal(mortgage, asOfDate, false, includeProjection);
    }

    /** Unsaved previews alone may include hypothetical future payment records. */
    public Summary summarizeScenario(Mortgage mortgage, LocalDate asOfDate) {
        return summarizeInternal(mortgage, asOfDate, true, true);
    }

    private Summary summarizeInternal(Mortgage mortgage, LocalDate asOfDate, boolean scenario, boolean includeProjection) {
        Replay replayed = replay(mortgage, asOfDate, earlier(asOfDate, LocalDate.now(clock)), scenario);
        Balance balance = replayed.balance();
        List<LocalDate> dues = dueDates(mortgage);
        boolean closed = mortgage.getCloseDate() != null && !mortgage.getCloseDate().isAfter(asOfDate);
        Mortgage.RatePeriod current = periodOn(mortgage, asOfDate);
        Mortgage.RatePeriod last = periods(mortgage).stream()
                .filter(p -> !p.getEffectiveFrom().isAfter(asOfDate)).reduce((a, b) -> b).orElse(null);
        LocalDate next = !closed && balance.principalBalance() != null && balance.principalBalance().signum() > 0
                ? dues.stream().filter(d -> d.isAfter(asOfDate)).findFirst().orElse(null) : null;
        // A known contractual installment is still useful even when a partial cycle blocks balance estimates.
        // An extra payment that lowered it replaced the period's installment from then on.
        BigDecimal payment = current == null ? null
                : replayed.installments().getOrDefault(periodKey(current), current.getMonthlyPayment());
        if (payment == null && current != null && balance.principalBalance() != null && next != null
                && !balance.partialPeriodUnsupported()) {
            payment = calculatedInstallment(mortgage, current, balance.principalBalance(), asOfDate);
        }
        if (payment != null) payment = money(payment, mortgage);
        BigDecimal share = mortgage.getDebtAllocationShare() == null ? ONE : mortgage.getDebtAllocationShare();
        BigDecimal recordedPrincipal = ZERO, recordedInterest = ZERO, recordedFees = ZERO;
        LocalDate knownThrough = earlier(asOfDate, LocalDate.now(clock));
        for (Mortgage.Payment p : payments(mortgage)) {
            if (p.getPaymentDate() == null || p.getPaymentDate().isAfter(knownThrough)) continue;
            recordedPrincipal = recordedPrincipal.add(amount(p.getPrincipalPaid())).add(amount(p.getExtraPrincipalPaid()));
            recordedInterest = recordedInterest.add(amount(p.getInterestPaid()));
            recordedFees = recordedFees.add(amount(p.getFeesPaid()));
        }
        LocalDate payoff = null;
        if (balance.principalBalance() != null && balance.principalBalance().signum() == 0) {
            payoff = balance.asOfDate();
        } else if (includeProjection && !closed && mortgage.getMaturityDate() != null && !mortgage.getMaturityDate().isBefore(asOfDate)) {
            Replay forecast = replay(mortgage, mortgage.getMaturityDate(), knownThrough, scenario);
            payoff = forecast.rows().stream().filter(r -> r.date().isAfter(asOfDate)
                            && r.balance() != null && r.balance().signum() == 0
                            && ("INSTALLMENT".equals(r.kind()) || "PAYMENT".equals(r.kind())))
                    .map(Installment::date).findFirst().orElse(null);
        }
        return new Summary(balance, next, payment, payment == null ? null : money(payment.multiply(share, MC), mortgage),
                current, last, closed ? 0 : remainingPaymentCount(mortgage, asOfDate),
                money(recordedPrincipal, mortgage), money(recordedInterest, mortgage), money(recordedFees, mortgage), payoff);
    }

    /** Future installments are projections; confirmed records later than today never enter this view. */
    public List<Installment> schedule(Mortgage mortgage, LocalDate from, LocalDate to, LocalDate today) {
        return schedule(mortgage, from, to, today, false);
    }

    public List<Installment> scheduleScenario(Mortgage mortgage, LocalDate from, LocalDate to, LocalDate today) {
        return schedule(mortgage, from, to, today, true);
    }

    private List<Installment> schedule(Mortgage mortgage, LocalDate from, LocalDate to, LocalDate today, boolean scenario) {
        if (from == null || to == null || today == null || to.isBefore(from)) {
            throw new IllegalArgumentException("A valid schedule date range is required");
        }
        // Dues stop at maturity, but a late bank settlement/checkpoint must remain visible afterwards.
        return replay(mortgage, to, earlier(today, to), scenario).rows().stream()
                .filter(r -> !r.date().isBefore(from) && !r.date().isAfter(to)).toList();
    }

    private Replay replay(Mortgage mortgage, LocalDate horizon, LocalDate knownThrough) {
        return replay(mortgage, horizon, knownThrough, false);
    }

    private Replay replay(Mortgage mortgage, LocalDate horizon, LocalDate knownThrough, boolean scenario) {
        Objects.requireNonNull(mortgage, "Mortgage is required");
        Objects.requireNonNull(horizon, "As-of date is required");
        List<Mortgage.BalanceCheckpoint> checkpoints = checkpoints(mortgage).stream()
                .filter(c -> c.getAsOfDate() != null && c.getPrincipalBalance() != null
                        && !c.getAsOfDate().isAfter(knownThrough)).toList();
        if (checkpoints.isEmpty() || mortgage.getLoanStartDate() != null && horizon.isBefore(mortgage.getLoanStartDate())) {
            return new Replay(new Balance(null, "UNKNOWN", null, null, false, false,
                    List.of("No opening bank balance is available for this date")), List.of(), Map.of());
        }
        Mortgage.BalanceCheckpoint first = checkpoints.getFirst();
        List<LocalDate> dues = dueDates(mortgage);
        List<Mortgage.Payment> knownPayments = payments(mortgage).stream()
                .filter(p -> p.getPaymentDate() != null && !p.getPaymentDate().isAfter(scenario ? horizon : knownThrough)).toList();
        List<Mortgage.Payment> actual = knownPayments.stream()
                .filter(p -> p.getPaymentDate().isAfter(first.getAsOfDate())).toList();
        Map<LocalDate, Mortgage.Payment> recordedDue = new HashMap<>();
        // An early installment may already be included in the opening bank balance; it still replaces its later due.
        knownPayments.stream().filter(p -> p.getScheduledDueDate() != null)
                .forEach(p -> recordedDue.put(p.getScheduledDueDate(), p));
        Map<LocalDate, List<Mortgage.Payment>> paymentsByDate = new HashMap<>();
        for (var payment : actual) paymentsByDate.computeIfAbsent(payment.getPaymentDate(), ignored -> new ArrayList<>()).add(payment);
        Map<LocalDate, List<Mortgage.BalanceCheckpoint>> checkpointsByDate = new HashMap<>();
        for (var checkpoint : checkpoints) checkpointsByDate.computeIfAbsent(checkpoint.getAsOfDate(), ignored -> new ArrayList<>()).add(checkpoint);
        Set<LocalDate> dueSet = Set.copyOf(dues);
        TreeSet<LocalDate> dates = new TreeSet<>();
        dates.add(first.getAsOfDate());
        dues.stream().filter(d -> d.isAfter(first.getAsOfDate()) && !d.isAfter(horizon)).forEach(dates::add);
        actual.stream().map(Mortgage.Payment::getPaymentDate).forEach(dates::add);
        checkpoints.stream().map(Mortgage.BalanceCheckpoint::getAsOfDate).forEach(dates::add);
        List<Installment> rows = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        Map<String, BigDecimal> installments = new HashMap<>();
        BigDecimal principal = money(first.getPrincipalBalance(), mortgage);
        LocalDate balanceDate = first.getAsOfDate(), lastConfirmed = first.getAsOfDate();
        boolean estimated = false, blocked = false, partial = false, rateGap = false;
        rows.add(row("OPENING", first.getAsOfDate(), null, ZERO, ZERO, ZERO, ZERO,
                principal, "CONFIRMED", null, "Opening end-of-day principal balance", mortgage));

        for (LocalDate date : dates) {
            if (date.isBefore(first.getAsOfDate()) || date.isAfter(horizon)) continue;
            boolean due = dueSet.contains(date) && date.isAfter(first.getAsOfDate());
            Mortgage.Payment recorded = recordedDue.get(date);
            if (due && principal.signum() > 0) {
                LocalDate cycleStart = previousDue(date, paymentDay(mortgage));
                LocalDate accrualStart = accrualStart(mortgage, cycleStart);
                LocalDate rateFrom = rateFrom(accrualStart, first.getAsOfDate(), date);
                Mortgage.RatePeriod period = periodForCycle(mortgage, rateFrom, date.minusDays(1));
                boolean hasActual = recorded != null;
                // Two known rates inside one cycle cannot share a single rate/12 month.
                boolean partialCycle = period == null && cycleFullyCovered(mortgage, rateFrom, date.minusDays(1));
                if (!hasActual && (period == null || partialCycle || blocked)) {
                    if (!"STATEMENT_ONLY".equals(mortgage.getBalanceTrackingMode())) {
                        blocked = true;
                        if (partialCycle || partial || period != null) {
                            partial = partial || partialCycle;
                            warnings.add(partial ? SPLIT_RATE_WARNING
                                    : "Confirm bank terms and balance before resuming unsupported estimates");
                            rows.add(row("UNSUPPORTED_PERIOD", date, date, null, null, null, null,
                                    principal, "PAUSED", null, partial ? "Rate change inside the cycle: estimates paused" : "Unsupported schedule: estimates paused", mortgage));
                        } else {
                            if (period == null) rateGap = true;
                            warnings.add(period == null ? "New rate needed; scheduled principal estimates have stopped"
                                    : "A bank balance is needed to resume estimates after an unsupported period");
                            rows.add(row("RATE_NEEDED", date, date, null, null, null, null,
                                    principal, "PAUSED", null, "Scheduled estimate unavailable", mortgage));
                        }
                    } else {
                        rows.add(row("INSTALLMENT", date, date, null, null, null, null,
                                principal, "UNRECORDED", period == null ? null : period.getAnnualNominalRatePercent(),
                                "No confirmed payment recorded; principal carried forward", mortgage));
                    }
                } else if (!hasActual) {
                    BigDecimal interest = money(prorate(principal.multiply(period.getAnnualNominalRatePercent(), MC)
                            .divide(TWELVE_HUNDRED, MC), accrualStart, cycleStart, date), mortgage);
                    String key = periodKey(period);
                    BigDecimal payment = installments.get(key);
                    if (payment == null) {
                        payment = period.getMonthlyPayment() != null ? money(period.getMonthlyPayment(), mortgage)
                                : calculatedInstallment(mortgage, period, principal, cycleStart);
                        if (payment != null) installments.put(key, payment);
                    }
                    if (payment == null || payment.compareTo(interest) <= 0 && principal.signum() > 0) {
                        blocked = true;
                        warnings.add("The installment does not amortize principal; confirm the bank's payment or loan type");
                        rows.add(row("UNSUPPORTED_PERIOD", date, date, null, null, interest, null,
                                principal, "PAUSED", period.getAnnualNominalRatePercent(), "Non-amortizing installment", mortgage));
                    } else {
                        BigDecimal paidPrincipal = payment.subtract(interest).min(principal).max(ZERO);
                        if (date.equals(dues.getLast())) {
                            BigDecimal remainder = principal.subtract(paidPrincipal);
                            BigDecimal tolerance = ONE.movePointLeft(scale(mortgage)).multiply(BigDecimal.valueOf(dues.size()));
                            if (remainder.compareTo(tolerance) <= 0) paidPrincipal = principal;
                            else warnings.add("Principal remains at contractual maturity; this schedule requires a bank-confirmed correction");
                        }
                        boolean future = date.isAfter(knownThrough);
                        boolean infer = future || !"STATEMENT_ONLY".equals(mortgage.getBalanceTrackingMode());
                        if (infer) {
                            principal = money(principal.subtract(paidPrincipal), mortgage);
                            estimated = true;
                            balanceDate = date;
                        }
                        rows.add(row("INSTALLMENT", date, date, paidPrincipal, ZERO, interest, ZERO,
                                principal, future ? "PROJECTED" : infer ? "ESTIMATED" : "UNRECORDED",
                                period.getAnnualNominalRatePercent(), infer ? null : "Not recorded as paid; balance unchanged", mortgage));
                    }
                } else if (!recorded.getPaymentDate().equals(date)) {
                    rows.add(row("INSTALLMENT", date, date, null, null, null, null, principal, "UNRECORDED",
                            period == null ? null : period.getAnnualNominalRatePercent(),
                            "Recorded installment is applied on " + recorded.getPaymentDate(), mortgage));
                }
            }

            for (Mortgage.Payment payment : paymentsByDate.getOrDefault(date, List.of())) {
                BigDecimal regular = amount(payment.getPrincipalPaid());
                BigDecimal extra = amount(payment.getExtraPrincipalPaid());
                BigDecimal paid = regular.add(extra);
                if (paid.compareTo(principal) > 0) warnings.add("Recorded principal exceeds the tracked balance; bank reconciliation needed");
                principal = money(principal.subtract(paid).max(ZERO), mortgage);
                balanceDate = date;
                boolean hypothetical = date.isAfter(knownThrough);
                if (hypothetical) estimated = true;
                if (!hypothetical && !estimated && !blocked) lastConfirmed = date;
                boolean missed = payment.getScheduledDueDate() != null && paid.signum() == 0
                        && amount(payment.getInterestPaid()).signum() == 0 && amount(payment.getFeesPaid()).signum() == 0;
                LocalDate paidDue = payment.getScheduledDueDate();
                Mortgage.RatePeriod paymentRate = paidDue == null ? periodOn(mortgage, date)
                        : periodForCycle(mortgage, rateFrom(accrualStart(mortgage, previousDue(paidDue, paymentDay(mortgage))),
                        first.getAsOfDate(), paidDue), paidDue.minusDays(1));
                BigDecimal lowered = Boolean.TRUE.equals(payment.getLowerInstallment())
                        ? lowerInstallment(mortgage, payment, date, principal, dues, first.getAsOfDate(), installments) : null;
                String note = missed ? "Recorded missed installment" : payment.getNote();
                if (lowered != null) note = (note == null || note.isBlank() ? "" : note + " · ") + "Installment lowered to " + lowered.toPlainString();
                rows.add(row("PAYMENT", date, payment.getScheduledDueDate(), regular, extra,
                        amount(payment.getInterestPaid()), amount(payment.getFeesPaid()), principal, hypothetical ? "PROJECTED" : "CONFIRMED",
                        paymentRate == null ? null : paymentRate.getAnnualNominalRatePercent(), note, mortgage));
            }

            for (Mortgage.BalanceCheckpoint checkpoint : checkpointsByDate.getOrDefault(date, List.of())) {
                if (checkpoint == first) continue;
                BigDecimal difference = money(checkpoint.getPrincipalBalance().subtract(principal), mortgage);
                principal = money(checkpoint.getPrincipalBalance(), mortgage);
                balanceDate = date;
                lastConfirmed = date;
                estimated = false;
                blocked = false;
                partial = false;
                rateGap = false;
                warnings.clear();
                rows.add(row("CHECKPOINT", date, null, ZERO, ZERO, ZERO, ZERO, principal,
                        "CONFIRMED", null, "Bank balance reconciliation: " + difference.toPlainString(), mortgage));
            }
        }
        LocalDate nextDue = dues.stream().filter(d -> d.isAfter(horizon)).findFirst().orElse(null);
        if (principal.signum() > 0 && nextDue != null && !recordedDue.containsKey(nextDue)) {
            LocalDate nextFrom = rateFrom(accrualStart(mortgage, previousDue(nextDue, paymentDay(mortgage))), first.getAsOfDate(), nextDue);
            if (periodForCycle(mortgage, nextFrom, nextDue.minusDays(1)) == null
                    && cycleFullyCovered(mortgage, nextFrom, nextDue.minusDays(1))) {
                partial = true;
                warnings.add(SPLIT_RATE_WARNING);
            }
        }
        boolean currentRateMissing = principal.signum() > 0 && periodOn(mortgage, horizon) == null;
        if (currentRateMissing) warnings.add("New rate needed; no agreed rate covers this date");
        boolean rateNeeded = principal.signum() > 0 && (currentRateMissing || rateGap);
        String provenance;
        if (blocked || currentRateMissing && !balanceDate.equals(horizon)) provenance = "PAUSED";
        else if (estimated) provenance = "ESTIMATED";
        else if (balanceDate.equals(horizon)) provenance = "CONFIRMED";
        else provenance = "CARRIED";
        return new Replay(new Balance(principal, provenance, balanceDate, lastConfirmed, rateNeeded,
                partial, List.copyOf(warnings)), List.copyOf(rows), Map.copyOf(installments));
    }

    private BigDecimal calculatedInstallment(Mortgage mortgage, Mortgage.RatePeriod period,
                                              BigDecimal currentPrincipal, LocalDate fallbackAnchor) {
        BigDecimal basis = period.getCalculationPrincipal() != null ? period.getCalculationPrincipal() : currentPrincipal;
        int count = period.getRemainingPaymentCount() != null ? period.getRemainingPaymentCount()
                : remainingPaymentCount(mortgage, period.getAnchorDate() != null ? period.getAnchorDate() : fallbackAnchor);
        if (count <= 0 || period.getAnnualNominalRatePercent() == null) return null;
        return monthlyPayment(basis, period.getAnnualNominalRatePercent(), count, scale(mortgage));
    }

    private static Installment row(String kind, LocalDate date, LocalDate scheduledDate, BigDecimal principal,
                                   BigDecimal extra, BigDecimal interest, BigDecimal fees, BigDecimal balance,
                                   String provenance, BigDecimal rate, String note, Mortgage mortgage) {
        BigDecimal total = principal == null || extra == null || interest == null || fees == null ? null
                : money(principal.add(extra).add(interest).add(fees), mortgage);
        return new Installment(kind, date, scheduledDate, nullableMoney(principal, mortgage),
                nullableMoney(extra, mortgage), nullableMoney(interest, mortgage), nullableMoney(fees, mortgage),
                total, nullableMoney(balance, mortgage), provenance, rate, note);
    }

    private static Mortgage.RatePeriod periodForCycle(Mortgage mortgage, LocalDate start, LocalDate end) {
        return periods(mortgage).stream().filter(p -> !p.getEffectiveFrom().isAfter(start)
                && !p.getFixedUntil().isBefore(end)).findFirst().orElse(null);
    }

    private static Mortgage.RatePeriod periodOn(Mortgage mortgage, LocalDate date) {
        return periods(mortgage).stream().filter(p -> !p.getEffectiveFrom().isAfter(date)
                && !p.getFixedUntil().isBefore(date)).findFirst().orElse(null);
    }

    /** Several known rates can cover a cycle without supporting the single-month rate/12 calculation. */
    private static boolean cycleFullyCovered(Mortgage mortgage, LocalDate start, LocalDate end) {
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            Mortgage.RatePeriod period = periodOn(mortgage, cursor);
            if (period == null) return false;
            if (!period.getFixedUntil().isBefore(end)) return true;
            cursor = period.getFixedUntil().plusDays(1);
        }
        return true;
    }

    /** A loan paid out inside a cycle accrues interest only from its payout. */
    private static LocalDate accrualStart(Mortgage mortgage, LocalDate cycleStart) {
        LocalDate payout = mortgage.getLoanStartDate();
        return payout != null && payout.isAfter(cycleStart) ? payout : cycleStart;
    }

    /** Before the opening balance nothing is tracked, so a cycle's rate is needed from there at the latest. */
    private static LocalDate rateFrom(LocalDate accrualStart, LocalDate opening, LocalDate due) {
        return opening.isAfter(accrualStart) && opening.isBefore(due) ? opening : accrualStart;
    }

    /**
     * An extra payment that lowers the installment keeps the end date: from the next due on, the rate
     * period in force pays the bank's new installment, or the annuity of what is left over the
     * installments left. A later rate period keeps its own installment. Null when no rate covers
     * the next due (the new rate's installment then follows from the lower balance anyway).
     */
    private BigDecimal lowerInstallment(Mortgage mortgage, Mortgage.Payment payment, LocalDate date, BigDecimal principal,
                                        List<LocalDate> dues, LocalDate opening, Map<String, BigDecimal> installments) {
        List<LocalDate> left = dues.stream().filter(d -> d.isAfter(date)).toList();
        if (left.isEmpty() || principal.signum() <= 0) return null;
        LocalDate next = left.getFirst();
        Mortgage.RatePeriod period = periodForCycle(mortgage,
                rateFrom(accrualStart(mortgage, previousDue(next, paymentDay(mortgage))), opening, next), next.minusDays(1));
        if (period == null) return null;
        BigDecimal installment = payment.getNewInstallment() != null ? money(payment.getNewInstallment(), mortgage)
                : monthlyPayment(principal, period.getAnnualNominalRatePercent(), left.size(), scale(mortgage));
        installments.put(periodKey(period), installment);
        return installment;
    }

    /** A month's interest, scaled to the days a loan paid out inside the cycle existed in it. */
    private static BigDecimal prorate(BigDecimal monthInterest, LocalDate accrualStart, LocalDate cycleStart, LocalDate due) {
        if (!accrualStart.isAfter(cycleStart)) return monthInterest;
        return monthInterest.multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(accrualStart, due)), MC)
                .divide(BigDecimal.valueOf(ChronoUnit.DAYS.between(cycleStart, due)), MC);
    }

    private static List<Mortgage.RatePeriod> periods(Mortgage mortgage) {
        return (mortgage.getRatePeriods() == null ? List.<Mortgage.RatePeriod>of() : mortgage.getRatePeriods()).stream()
                .filter(p -> p.getEffectiveFrom() != null && p.getFixedUntil() != null && p.getAnnualNominalRatePercent() != null)
                .sorted(Comparator.comparing(Mortgage.RatePeriod::getEffectiveFrom)).toList();
    }

    private static List<Mortgage.BalanceCheckpoint> checkpoints(Mortgage mortgage) {
        return (mortgage.getBalanceCheckpoints() == null ? List.<Mortgage.BalanceCheckpoint>of() : mortgage.getBalanceCheckpoints())
                .stream().filter(c -> c.getAsOfDate() != null).sorted(Comparator.comparing(Mortgage.BalanceCheckpoint::getAsOfDate)).toList();
    }

    private static List<Mortgage.Payment> payments(Mortgage mortgage) {
        return (mortgage.getPayments() == null ? List.<Mortgage.Payment>of() : mortgage.getPayments()).stream()
                .sorted(Comparator.comparing(Mortgage.Payment::getPaymentDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public List<LocalDate> dueDates(Mortgage mortgage) {
        if (mortgage.getFirstPaymentDate() == null || mortgage.getMaturityDate() == null) return List.of();
        List<LocalDate> dates = new ArrayList<>();
        YearMonth month = YearMonth.from(mortgage.getFirstPaymentDate());
        int day = paymentDay(mortgage);
        for (int i = 0; i < MAX_PAYMENTS; i++) {
            YearMonth current = month.plusMonths(i);
            LocalDate due = current.atDay(Math.min(day, current.lengthOfMonth()));
            if (due.isAfter(mortgage.getMaturityDate())) break;
            if (!due.isBefore(mortgage.getFirstPaymentDate())) dates.add(due);
        }
        return dates;
    }

    private static LocalDate previousDue(LocalDate due, int day) {
        YearMonth month = YearMonth.from(due).minusMonths(1);
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    private static int paymentDay(Mortgage mortgage) {
        int day = mortgage.getPaymentDay() == null ? (mortgage.getFirstPaymentDate() == null ? 1
                : mortgage.getFirstPaymentDate().getDayOfMonth()) : mortgage.getPaymentDay();
        return Math.max(1, Math.min(31, day));
    }

    private static String periodKey(Mortgage.RatePeriod period) {
        return period.getId() == null ? period.getEffectiveFrom().toString() : period.getId();
    }

    private static BigDecimal amount(BigDecimal amount) { return amount == null ? ZERO : amount; }
    private static LocalDate earlier(LocalDate a, LocalDate b) { return a.isBefore(b) ? a : b; }
    private static BigDecimal nullableMoney(BigDecimal value, Mortgage mortgage) { return value == null ? null : money(value, mortgage); }
    private static BigDecimal money(BigDecimal value, Mortgage mortgage) { return value.setScale(scale(mortgage), RoundingMode.HALF_UP); }
    private static int scale(Mortgage mortgage) {
        try { return Math.max(0, Currency.getInstance(mortgage.getCurrency()).getDefaultFractionDigits()); }
        catch (IllegalArgumentException | NullPointerException ignored) { return 2; }
    }
}
