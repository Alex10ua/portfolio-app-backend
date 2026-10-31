package com.dev.alex.Service;

import com.dev.alex.Model.Mortgage;
import com.dev.alex.Model.RealEstateProperty;
import com.dev.alex.Model.NonDbModel.RealEstateCommands.*;
import com.dev.alex.Repository.MortgageRepository;
import com.dev.alex.Repository.RealEstatePropertyRepository;
import com.dev.alex.Service.Interface.RealEstateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RealEstateServiceImpl implements RealEstateService {
    @Autowired private RealEstatePropertyRepository propertyRepository;
    @Autowired private MortgageRepository mortgageRepository;
    @Autowired private MortgageCalculationService calculations;
    Clock clock = Clock.systemDefaultZone();
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int MAX_EVENTS = 1200;
    private static final Set<String> PROPERTY_TYPES = Set.of("APARTMENT", "HOUSE", "LAND", "COMMERCIAL", "OTHER");
    private static final Set<String> USAGES = Set.of("HOME", "RENTAL", "OTHER");
    private static final Set<String> FINANCING = Set.of("MORTGAGE_FREE", "MORTGAGE_SETUP_PENDING", "MORTGAGE_RECORDED");
    private static final Set<String> VALUATION_SOURCES = Set.of("OWNER_ESTIMATE", "APPRAISAL", "PURCHASE");
    // Only the pre-multi-loan unique index can still reject a new loan id; RealEstateIndexMigration drops it.
    private static final String LEGACY_INDEX_MESSAGE =
            "The database still allows only one active loan per property; restart the backend so it can remove the old index";

    public record PropertyView(RealEstateProperty property, List<Mortgage> mortgages,
            RealEstateProperty.Valuation latestValuation, BigDecimal fullValue, BigDecimal ownedValue,
            BigDecimal attributedMortgagePrincipal, BigDecimal equity, boolean complete,
            Map<String, MortgageCalculationService.Summary> loanSummaries, DebtStatus debt) {}
    /**
     * The open loans taken together, null when none is open: the least certain balance, every
     * warning (named by loan when there are several), and the installments still being paid.
     * attributedMonthlyPayment is null when any of those installments is unknown.
     */
    public record DebtStatus(String provenance, boolean rateNeeded, boolean partialPeriodUnsupported,
            List<String> warnings, BigDecimal attributedMonthlyPayment, LocalDate nextDueDate,
            List<String> loansNeedingAttention) {}
    public record MortgageView(Mortgage mortgage, MortgageCalculationService.Summary summary) {}
    public record Preview(MortgageCalculationService.Summary summary,
                          List<MortgageCalculationService.Installment> schedule) {}
    /** One projection: the installment after the payment, when the loan ends, and the interest still to pay from today. */
    public record ExtraPaymentOutcome(BigDecimal installment, LocalDate payoffDate, BigDecimal remainingInterest, int installmentsLeft) {}
    /**
     * Unsaved: the loan as it is, then with the extra payment ending it sooner or lowering the
     * installment. assumedRatePercent is set when that rate was carried past assumedAfter, its fixation end.
     */
    public record ExtraPaymentPreview(LocalDate date, BigDecimal amount, ExtraPaymentOutcome current,
            ExtraPaymentOutcome keepInstallment, ExtraPaymentOutcome lowerInstallment,
            BigDecimal assumedRatePercent, LocalDate assumedAfter) {}

    /** All domain errors retain a useful HTTP status through the controller's local handler. */
    public static class RealEstateException extends RuntimeException {
        private final int status;
        public RealEstateException(int status, String message) { super(message); this.status = status; }
        public int getStatus() { return status; }
    }

    @Override
    public List<PropertyView> viewsFor(String portfolioId, LocalDate asOf) {
        List<Mortgage> loans = mortgageRepository.findAllByPortfolioId(portfolioId);
        return propertyRepository.findAllByPortfolioId(portfolioId).stream()
                .sorted(Comparator.comparing(RealEstateProperty::getName, String.CASE_INSENSITIVE_ORDER))
                .map(property -> viewFor(property, loans, asOf)).toList();
    }

    /** Loaded records can be reused for every history date without an N×months database query. */
    public PropertyView viewFor(RealEstateProperty property, List<Mortgage> loans, LocalDate asOf) {
        return viewFor(property, loans, asOf, true);
    }

    public PropertyView viewFor(RealEstateProperty property, List<Mortgage> loans, LocalDate asOf, boolean includeProjection) {
        List<Mortgage> linked = loans.stream().filter(m -> Objects.equals(property.getId(), m.getPropertyId())
                        && Objects.equals(property.getPortfolioId(), m.getPortfolioId()))
                .sorted(Comparator.comparing(Mortgage::getLoanStartDate)).toList();
        RealEstateProperty.Valuation valuation = property.getValuations().stream()
                .filter(v -> !v.getEffectiveDate().isAfter(asOf))
                .max(Comparator.comparing(RealEstateProperty.Valuation::getEffectiveDate)).orElse(null);
        boolean included = !asOf.isBefore(property.getTrackingStartDate())
                && (property.getArchiveDate() == null || asOf.isBefore(property.getArchiveDate()));
        BigDecimal full = valuation == null ? null : valuation.getFullPropertyValue();
        BigDecimal owned = full == null ? null : full.multiply(property.getOwnershipShare());
        boolean debtComplete = !"MORTGAGE_SETUP_PENDING".equals(property.getFinancingStatus());
        BigDecimal attributed = ZERO;
        Map<String, MortgageCalculationService.Summary> summaries = new LinkedHashMap<>();
        for (Mortgage loan : linked) {
            if (asOf.isBefore(loan.getLoanStartDate())) continue;
            var balance = calculations.balanceAt(loan, asOf);
            BigDecimal allocation = Objects.requireNonNullElse(loan.getDebtAllocationShare(), BigDecimal.ONE);
            // A loan tracked with no allocation contributes exactly zero debt even when its
            // full bank balance is unavailable. Its summary still exposes the missing records.
            if (balance.principalBalance() == null) {
                if (allocation.signum() != 0) debtComplete = false;
            } else attributed = attributed.add(balance.principalBalance().multiply(allocation));
            if (loan.getCloseDate() == null || !loan.getCloseDate().isBefore(asOf)) {
                summaries.put(loan.getId(), calculations.summarize(loan, asOf, includeProjection));
            }
        }
        if ("MORTGAGE_RECORDED".equals(property.getFinancingStatus()) && linked.isEmpty()) debtComplete = false;
        boolean complete = included && full != null && debtComplete;
        BigDecimal equity = complete ? owned.subtract(attributed) : null;
        return new PropertyView(property, linked, valuation, full, owned,
                debtComplete ? attributed : null, equity, complete, summaries, debtStatus(linked, summaries));
    }

    private static final List<String> CONFIDENCE = List.of("CONFIRMED", "CARRIED", "ESTIMATED", "PAUSED", "UNKNOWN");

    private static DebtStatus debtStatus(List<Mortgage> linked, Map<String, MortgageCalculationService.Summary> summaries) {
        if (summaries.isEmpty()) return null;
        String provenance = CONFIDENCE.getFirst();
        boolean rateNeeded = false, partial = false, paymentKnown = true;
        BigDecimal payment = ZERO;
        LocalDate next = null;
        List<String> warnings = new ArrayList<>(), attention = new ArrayList<>();
        for (Mortgage loan : linked) {
            var summary = summaries.get(loan.getId());
            if (summary == null) continue;
            var balance = summary.balance();
            if (CONFIDENCE.indexOf(balance.provenance()) > CONFIDENCE.indexOf(provenance)) provenance = balance.provenance();
            rateNeeded |= balance.rateNeeded();
            partial |= balance.partialPeriodUnsupported();
            if (balance.rateNeeded() || balance.partialPeriodUnsupported()) attention.add(loan.getId());
            String label = summaries.size() > 1 ? loanLabel(loan) + ": " : "";
            for (String warning : balance.warnings()) warnings.add(label + warning);
            // A loan with no installment left (paid off, closed today) adds nothing to the monthly total.
            if (summary.nextDueDate() == null) continue;
            if (summary.attributedMonthlyPayment() == null) paymentKnown = false;
            else payment = payment.add(summary.attributedMonthlyPayment());
            if (next == null || summary.nextDueDate().isBefore(next)) next = summary.nextDueDate();
        }
        return new DebtStatus(provenance, rateNeeded, partial, List.copyOf(warnings), paymentKnown ? payment : null, next,
                List.copyOf(attention));
    }

    private static String loanLabel(Mortgage loan) {
        if (loan.getName() != null && !loan.getName().isBlank()) return loan.getName();
        return loan.getLender() != null && !loan.getLender().isBlank() ? loan.getLender() : "Loan";
    }

    @Override
    public RealEstateProperty requireProperty(String portfolioId, String propertyId) {
        return propertyRepository.findByIdAndPortfolioId(propertyId, portfolioId)
                .orElseThrow(() -> fail(404, "Property not found"));
    }

    public Mortgage requireMortgage(String portfolioId, String propertyId, String mortgageId) {
        requireProperty(portfolioId, propertyId);
        return mortgageRepository.findByIdAndPortfolioIdAndPropertyId(mortgageId, portfolioId, propertyId)
                .orElseThrow(() -> fail(404, "Mortgage not found"));
    }

    public PropertyView getProperty(String portfolioId, String propertyId) {
        return viewFor(requireProperty(portfolioId, propertyId),
                mortgageRepository.findAllByPortfolioIdAndPropertyId(portfolioId, propertyId), today());
    }

    public List<Mortgage> getMortgages(String portfolioId, String propertyId) {
        requireProperty(portfolioId, propertyId);
        return mortgageRepository.findAllByPortfolioIdAndPropertyId(portfolioId, propertyId);
    }

    public MortgageView getMortgage(String portfolioId, String propertyId, String mortgageId) {
        Mortgage loan = requireMortgage(portfolioId, propertyId, mortgageId);
        return new MortgageView(loan, calculations.summarize(loan, today()));
    }

    public PropertyView createProperty(String portfolioId, PropertyCommand command, String key) {
        String requestKey = requestKey(key);
        String id = stableId(portfolioId, "property", requestKey);
        String fingerprint = fingerprint(command);
        var replay = propertyRepository.findByIdAndPortfolioId(id, portfolioId);
        if (replay.isPresent()) {
            sameFingerprint(replay.get().getCreationFingerprint(), command);
            return getProperty(portfolioId, id);
        }
        RealEstateProperty property = new RealEstateProperty();
        property.setId(id);
        property.setPortfolioId(portfolioId);
        applyProperty(property, command, true);
        if ("MORTGAGE_RECORDED".equals(property.getFinancingStatus())) {
            throw fail(400, "Choose mortgage free or finish mortgage setup after saving the property");
        }
        LocalDate valuationDate = command.valuationDate() == null ? property.getTrackingStartDate() : command.valuationDate();
        validActualDate(valuationDate, "Valuation date");
        if (valuationDate.isBefore(property.getTrackingStartDate())) throw fail(400, "Opening valuation cannot precede tracking start");
        property.getValuations().add(new RealEstateProperty.Valuation(UUID.randomUUID().toString(), valuationDate,
                money(command.fullPropertyValue(), "Estimated property value", false),
                choice(command.valuationSource(), VALUATION_SOURCES, "Valuation source", "OWNER_ESTIMATE"),
                null, now()));
        property.setStatus("ACTIVE");
        property.setCreatedAt(now());
        property.setUpdatedAt(now());
        property.setCreationFingerprint(fingerprint);
        try { propertyRepository.insert(property); }
        catch (DuplicateKeyException e) {
            RealEstateProperty existing = requireProperty(portfolioId, id);
            sameFingerprint(existing.getCreationFingerprint(), command);
        }
        return getProperty(portfolioId, id);
    }

    public PropertyView updateProperty(String portfolioId, String propertyId, PropertyCommand command, String key) {
        RealEstateProperty property = requireProperty(portfolioId, propertyId);
        String receiptKey = "property:" + requestKey(key);
        if (replayed(property.getRetryReceipts(), receiptKey, command)) return getProperty(portfolioId, propertyId);
        version(property.getVersion(), command.version());
        active(property);
        String previous = property.getFinancingStatus();
        applyProperty(property, command, false);
        List<Mortgage> linked = getMortgages(portfolioId, propertyId);
        boolean open = linked.stream().anyMatch(m -> m.getCloseDate() == null);
        if ("MORTGAGE_FREE".equals(property.getFinancingStatus()) && open) {
            throw fail(409, "Close the outstanding mortgage before marking the property mortgage free");
        }
        // A pending declaration stays pending: with several loans, one being recorded does not
        // mean the declared one is. Only the caller confirms that every loan is recorded, and
        // only while one is open; otherwise the declared loan still needs mortgage setup.
        if ("MORTGAGE_SETUP_PENDING".equals(previous) && "MORTGAGE_RECORDED".equals(property.getFinancingStatus()) && !open) {
            throw fail(400, "Add the mortgage through mortgage setup");
        }
        save(property, receiptKey, command);
        return getProperty(portfolioId, propertyId);
    }

    public PropertyView valuation(String portfolioId, String propertyId, String valuationId,
                                  ValuationCommand command, String key) {
        RealEstateProperty property = requireProperty(portfolioId, propertyId);
        String receiptKey = "valuation:" + (valuationId == null ? "new" : valuationId) + ":" + requestKey(key);
        if (replayed(property.getRetryReceipts(), receiptKey, command)) return getProperty(portfolioId, propertyId);
        version(property.getVersion(), command.version());
        validActualDate(command.effectiveDate(), "Valuation date");
        if (command.effectiveDate().isBefore(property.getTrackingStartDate())) throw fail(400, "Valuation date cannot precede tracking start");
        if (property.getArchiveDate() != null && !command.effectiveDate().isBefore(property.getArchiveDate())) {
            throw fail(400, "Valuation date must be before the property was archived");
        }
        RealEstateProperty.Valuation valuation;
        if (valuationId == null) {
            if (property.getValuations().size() >= MAX_EVENTS) throw fail(400, "Valuation history limit reached");
            valuation = new RealEstateProperty.Valuation();
            valuation.setId(UUID.randomUUID().toString());
            property.getValuations().add(valuation);
        } else valuation = property.getValuations().stream().filter(v -> valuationId.equals(v.getId())).findFirst()
                .orElseThrow(() -> fail(404, "Valuation not found"));
        if (property.getValuations().stream().anyMatch(v -> v != valuation && command.effectiveDate().equals(v.getEffectiveDate()))) {
            throw fail(409, "A valuation already exists on this date; edit it instead");
        }
        valuation.setEffectiveDate(command.effectiveDate());
        valuation.setFullPropertyValue(money(command.fullPropertyValue(), "Estimated property value", false));
        valuation.setSource(choice(command.source(), VALUATION_SOURCES, "Valuation source", "OWNER_ESTIMATE"));
        valuation.setNote(optional(command.note(), 2000));
        valuation.setRecordedAt(now());
        property.getValuations().sort(Comparator.comparing(RealEstateProperty.Valuation::getEffectiveDate));
        save(property, receiptKey, command);
        return getProperty(portfolioId, propertyId);
    }

    public PropertyView archive(String portfolioId, String propertyId, DateCommand command, String key) {
        RealEstateProperty property = requireProperty(portfolioId, propertyId);
        String receiptKey = "archive:" + requestKey(key);
        if (replayed(property.getRetryReceipts(), receiptKey, command)) return getProperty(portfolioId, propertyId);
        version(property.getVersion(), command.version());
        active(property);
        validActualDate(command.date(), "Archive date");
        if (command.date().isBefore(property.getTrackingStartDate())) throw fail(400, "Archive date cannot precede tracking start");
        if ("MORTGAGE_SETUP_PENDING".equals(property.getFinancingStatus())) throw fail(409, "Finish mortgage setup before archiving");
        for (Mortgage loan : getMortgages(portfolioId, propertyId)) {
            if (loan.getCloseDate() == null || loan.getCloseDate().isAfter(command.date())) {
                throw fail(409, "Close every loan with a confirmed zero balance before archiving");
            }
        }
        property.setArchiveDate(command.date());
        property.setStatus("ARCHIVED");
        save(property, receiptKey, command);
        return getProperty(portfolioId, propertyId);
    }

    /**
     * Permanently removes a property with its valuations and every mortgage on it — for a record
     * entered by mistake. A sold property is archived instead, which keeps its history.
     * An already-deleted property succeeds, so a retried DELETE is safe.
     */
    public void deleteProperty(String portfolioId, String propertyId, Long expectedVersion) {
        var found = propertyRepository.findByIdAndPortfolioId(propertyId, portfolioId);
        if (found.isEmpty()) return;
        RealEstateProperty property = found.get();
        version(property.getVersion(), expectedVersion);
        // Loans first: a failure between the two leaves the property with debt it cannot compute
        // (equity unknown), never a property that silently lost its mortgage.
        mortgageRepository.deleteAll(mortgageRepository.findAllByPortfolioIdAndPropertyId(portfolioId, propertyId));
        try { propertyRepository.delete(property); }
        catch (OptimisticLockingFailureException e) { throw fail(409, "Property changed elsewhere; reload before deleting"); }
    }

    public PropertyView createMortgage(String portfolioId, String propertyId, MortgageCommand command, String key) {
        String requestKey = requestKey(key);
        String id = stableId(portfolioId, "mortgage:" + propertyId, requestKey);
        String fingerprint = fingerprint(command);
        RealEstateProperty property = requireProperty(portfolioId, propertyId);
        var existing = mortgageRepository.findByIdAndPortfolioIdAndPropertyId(id, portfolioId, propertyId);
        if (existing.isPresent()) {
            sameFingerprint(existing.get().getCreationFingerprint(), command);
            recordFinancing(property);
            return getProperty(portfolioId, propertyId);
        }
        active(property);
        // Several loans may be open at once, so "an active loan exists" no longer proves this
        // command was already applied. Only a loan set up from the same input does: its insert
        // committed before the parent acknowledgement failed, and a reload lost the retry key.
        String setup = setupFingerprint(command);
        var committed = getMortgages(portfolioId, propertyId).stream()
                .filter(m -> "ACTIVE".equals(m.getStatus()) && setup.equals(m.getSetupFingerprint())).findFirst();
        if (committed.isPresent()) {
            if (!"MORTGAGE_SETUP_PENDING".equals(property.getFinancingStatus())) throw fail(409, "This loan is already recorded on the property");
            recordFinancing(property);
            return getProperty(portfolioId, propertyId);
        }
        version(property.getVersion(), command.propertyVersion());
        Mortgage loan = new Mortgage();
        loan.setId(id);
        loan.setPortfolioId(portfolioId);
        loan.setPropertyId(propertyId);
        applyMortgage(loan, property, command, true);
        if (command.openingBalance() == null) throw fail(400, "An opening bank balance is required");
        loan.getBalanceCheckpoints().add(balanceValue(loan, command.openingBalance(), null));
        if (command.ratePeriod() != null) loan.getRatePeriods().add(rateValue(loan, command.ratePeriod(), null));
        loan.setStatus("ACTIVE");
        loan.setCreatedAt(now());
        loan.setUpdatedAt(now());
        loan.setCreationFingerprint(fingerprint);
        loan.setSetupFingerprint(setup);
        // The parent is conservatively incomplete before any separate loan insert can succeed/fail.
        if (!"MORTGAGE_SETUP_PENDING".equals(property.getFinancingStatus())) {
            property.setFinancingStatus("MORTGAGE_SETUP_PENDING");
            savePropertyVersioned(property);
        }
        try { mortgageRepository.insert(loan); }
        catch (DuplicateKeyException e) {
            Mortgage replay = mortgageRepository.findByIdAndPortfolioIdAndPropertyId(id, portfolioId, propertyId)
                    .orElseThrow(() -> fail(409, LEGACY_INDEX_MESSAGE));
            sameFingerprint(replay.getCreationFingerprint(), command);
        }
        recordFinancing(requireProperty(portfolioId, propertyId));
        return getProperty(portfolioId, propertyId);
    }

    public PropertyView updateMortgage(String portfolioId, String propertyId, String mortgageId,
                                      MortgageCommand command, String key) {
        Mortgage loan = requireMortgage(portfolioId, propertyId, mortgageId);
        String receiptKey = "mortgage:" + requestKey(key);
        if (replayed(loan.getRetryReceipts(), receiptKey, command)) return getProperty(portfolioId, propertyId);
        version(loan.getVersion(), command.version());
        if (!"ACTIVE".equals(loan.getStatus())) throw fail(409, "A closed mortgage cannot be changed");
        MortgageCommand merged = new MortgageCommand(command.version(), command.propertyVersion(),
                command.name() == null ? loan.getName() : command.name(),
                command.lender() == null ? loan.getLender() : command.lender(), loan.getCurrency(),
                command.originalPrincipal() == null ? loan.getOriginalPrincipal() : command.originalPrincipal(),
                command.loanStartDate() == null ? loan.getLoanStartDate() : command.loanStartDate(),
                command.firstPaymentDate() == null ? loan.getFirstPaymentDate() : command.firstPaymentDate(),
                command.maturityDate() == null ? loan.getMaturityDate() : command.maturityDate(),
                command.paymentDay() == null ? loan.getPaymentDay() : command.paymentDay(),
                command.debtAllocationShare() == null ? loan.getDebtAllocationShare() : command.debtAllocationShare(),
                command.balanceTrackingMode() == null ? loan.getBalanceTrackingMode() : command.balanceTrackingMode(), null, null);
        applyMortgage(loan, requireProperty(portfolioId, propertyId), merged, false);
        save(loan, receiptKey, command);
        return getProperty(portfolioId, propertyId);
    }

    public PropertyView payment(String portfolioId, String propertyId, String mortgageId, String paymentId,
                                PaymentCommand command, String key) {
        return mutateLoan(portfolioId, propertyId, mortgageId, command.version(),
                "payment:" + Objects.toString(paymentId, "new"), command, key, loan -> {
            validLoanActualDate(loan, command.paymentDate(), "Payment date");
            BigDecimal principal = money(command.principalPaid(), "Principal", true);
            BigDecimal extra = money(command.extraPrincipalPaid(), "Extra principal", true);
            // Lowering the installment (same end date) instead of ending the loan sooner follows an extra repayment only.
            boolean lower = Boolean.TRUE.equals(command.lowerInstallment());
            if (lower && extra.signum() <= 0) throw fail(400, "Lowering the installment needs an extra principal payment");
            BigDecimal newInstallment = lower && command.newInstallment() != null ? money(command.newInstallment(), "New installment", false) : null;
            LocalDate scheduledDue = command.scheduledDueDate() == null && principal.signum() > 0 && dueOn(loan, command.paymentDate())
                    ? command.paymentDate() : command.scheduledDueDate();
            if (scheduledDue != null) {
                if (!dueOn(loan, scheduledDue)) throw fail(400, "Scheduled due date is not an installment date");
            }
            Mortgage.Payment payment;
            boolean creating = paymentId == null;
            if (paymentId == null) {
                if (loan.getPayments().size() >= MAX_EVENTS) throw fail(400, "Payment history limit reached");
                payment = new Mortgage.Payment();
                payment.setId(UUID.randomUUID().toString());
            } else payment = loan.getPayments().stream().filter(p -> paymentId.equals(p.getId())).findFirst()
                    .orElseThrow(() -> fail(404, "Payment not found"));
            if (scheduledDue != null && loan.getPayments().stream().anyMatch(p -> p != payment
                    && scheduledDue.equals(p.getScheduledDueDate()))) {
                throw fail(409, "An actual or missed installment is already recorded for this due date");
            }
            Mortgage beforePayment = copyLoan(loan);
            if (paymentId != null) beforePayment.getPayments().removeIf(p -> paymentId.equals(p.getId()));
            // Suppress a matching inferred installment while measuring the principal available to this actual payment.
            if (scheduledDue != null) beforePayment.getPayments().add(new Mortgage.Payment("validation",
                    command.paymentDate(), scheduledDue, ZERO, ZERO, ZERO, ZERO, null, now(), null, null));
            boolean endOfDayCheckpoint = loan.getBalanceCheckpoints().stream()
                    .anyMatch(c -> c.getAsOfDate().equals(command.paymentDate()));
            var before = calculations.balanceAt(beforePayment, endOfDayCheckpoint
                    ? command.paymentDate().minusDays(1) : command.paymentDate());
            BigDecimal available = before.principalBalance();
            if (available != null && endOfDayCheckpoint) {
                BigDecimal alreadyPaid = beforePayment.getPayments().stream()
                        .filter(p -> command.paymentDate().equals(p.getPaymentDate()))
                        .map(p -> Objects.requireNonNullElse(p.getPrincipalPaid(), ZERO)
                                .add(Objects.requireNonNullElse(p.getExtraPrincipalPaid(), ZERO)))
                        .reduce(ZERO, BigDecimal::add);
                available = available.subtract(alreadyPaid).max(ZERO);
            }
            // An end-of-day checkpoint already contains this day's payments. Its closing
            // balance cannot be used as the principal available before those payments.
            if (available != null && principal.add(extra).compareTo(available) > 0) {
                throw fail(400, "Principal repayment exceeds the remaining principal; reconcile the bank balance first");
            }
            if (newInstallment != null && available != null) {
                BigDecimal left = available.subtract(principal).subtract(extra);
                var rate = loan.getRatePeriods().stream().filter(r -> !r.getEffectiveFrom().isAfter(command.paymentDate())
                        && !r.getFixedUntil().isBefore(command.paymentDate())).findFirst();
                if (rate.isPresent() && left.signum() > 0 && newInstallment.compareTo(left.multiply(rate.get().getAnnualNominalRatePercent())
                        .divide(new BigDecimal("1200"), 12, RoundingMode.HALF_UP)) <= 0) {
                    throw fail(400, "The new installment must exceed a month's interest on what is left");
                }
            }
            boolean wasLowering = Boolean.TRUE.equals(payment.getLowerInstallment());
            LocalDate since = payment.getPaymentDate() == null || command.paymentDate().isBefore(payment.getPaymentDate())
                    ? command.paymentDate() : payment.getPaymentDate();
            payment.setPaymentDate(command.paymentDate());
            payment.setScheduledDueDate(scheduledDue);
            payment.setPrincipalPaid(principal);
            payment.setExtraPrincipalPaid(extra);
            payment.setInterestPaid(money(command.interestPaid(), "Interest", true));
            payment.setFeesPaid(money(command.feesPaid(), "Fees", true));
            payment.setNote(optional(command.note(), 2000));
            payment.setLowerInstallment(lower ? Boolean.TRUE : null);
            payment.setNewInstallment(newInstallment);
            payment.setRecordedAt(now());
            if (creating) loan.getPayments().add(payment);
            loan.getPayments().sort(Comparator.comparing(Mortgage.Payment::getPaymentDate));
            if (lower || wasLowering) rebaseLaterRates(loan, since, date -> calculations.balanceAt(loan, date).principalBalance());
        });
    }

    public PropertyView checkpoint(String portfolioId, String propertyId, String mortgageId, String checkpointId,
                                   BalanceCommand command, String key) {
        return mutateLoan(portfolioId, propertyId, mortgageId, command.version(),
                "checkpoint:" + Objects.toString(checkpointId, "new"), command, key, loan -> {
            if (loan.getBalanceCheckpoints().size() >= MAX_EVENTS && checkpointId == null) throw fail(400, "Balance history limit reached");
            Mortgage.BalanceCheckpoint value = balanceValue(loan, command, checkpointId);
            if (loan.getBalanceCheckpoints().stream().anyMatch(p -> !p.getId().equals(value.getId())
                    && p.getAsOfDate().equals(value.getAsOfDate()))) throw fail(409, "A bank balance already exists on this date; edit it instead");
            if (checkpointId != null && !loan.getBalanceCheckpoints().removeIf(p -> checkpointId.equals(p.getId()))) {
                throw fail(404, "Balance checkpoint not found");
            }
            loan.getBalanceCheckpoints().add(value);
            loan.getBalanceCheckpoints().sort(Comparator.comparing(Mortgage.BalanceCheckpoint::getAsOfDate));
        });
    }

    public PropertyView rate(String portfolioId, String propertyId, String mortgageId, String rateId,
                             RateCommand command, String key) {
        return mutateLoan(portfolioId, propertyId, mortgageId, command.version(),
                "rate:" + Objects.toString(rateId, "new"), command, key, loan -> {
            if (loan.getRatePeriods().size() >= MAX_EVENTS && rateId == null) throw fail(400, "Rate history limit reached");
            if (rateId != null && loan.getRatePeriods().stream().noneMatch(p -> rateId.equals(p.getId()))) throw fail(404, "Rate period not found");
            Mortgage.RatePeriod value = rateValue(loan, command, rateId);
            for (var prior : loan.getRatePeriods()) {
                if (Objects.equals(prior.getId(), rateId)) continue;
                if (!value.getEffectiveFrom().isAfter(prior.getFixedUntil()) && !prior.getEffectiveFrom().isAfter(value.getFixedUntil())) {
                    throw fail(409, "Fixed-rate periods cannot overlap; correct the prior period if necessary");
                }
            }
            if (rateId != null) loan.getRatePeriods().removeIf(p -> rateId.equals(p.getId()));
            loan.getRatePeriods().add(value);
            loan.getRatePeriods().sort(Comparator.comparing(Mortgage.RatePeriod::getEffectiveFrom));
        });
    }

    public PropertyView close(String portfolioId, String propertyId, String mortgageId, DateCommand command, String key) {
        return mutateLoan(portfolioId, propertyId, mortgageId, command.version(), "close", command, key, loan -> {
            validLoanActualDate(loan, command.date(), "Close date");
            boolean zero = loan.getBalanceCheckpoints().stream().anyMatch(c -> c.getAsOfDate().equals(command.date())
                    && c.getPrincipalBalance().signum() == 0);
            if (!zero) throw fail(409, "Record a confirmed zero bank balance on the close date first");
            if (loan.getPayments().stream().anyMatch(p -> p.getPaymentDate().isAfter(command.date()))
                    || loan.getBalanceCheckpoints().stream().anyMatch(c -> c.getAsOfDate().isAfter(command.date()))) {
                throw fail(400, "Close date cannot precede recorded payments or balances");
            }
            loan.setCloseDate(command.date());
            loan.setStatus("CLOSED");
        });
    }

    /**
     * Permanently removes one loan and its history — for a loan entered by mistake; a repaid loan
     * is closed instead. Removing the active loan needs the caller to say what the property's
     * financing is afterwards (MORTGAGE_SETUP_PENDING: a corrected loan follows, or MORTGAGE_FREE):
     * unknown financing must never read as mortgage free, so it is not inferred.
     * An already-deleted loan succeeds, so a retried DELETE is safe.
     */
    public PropertyView deleteMortgage(String portfolioId, String propertyId, String mortgageId,
                                       Long expectedVersion, String financingAfter) {
        RealEstateProperty property = requireProperty(portfolioId, propertyId);
        var found = mortgageRepository.findByIdAndPortfolioIdAndPropertyId(mortgageId, portfolioId, propertyId);
        if (found.isEmpty()) {
            // The delete may have committed before its final parent acknowledgement failed.
            // Only the caller's explicit choice can resolve unknown financing, and only one that
            // fits the loans left: mortgage free with none open, "the rest is all" with some.
            String settled = getMortgages(portfolioId, propertyId).stream().anyMatch(m -> "ACTIVE".equals(m.getStatus()))
                    ? "MORTGAGE_RECORDED" : "MORTGAGE_FREE";
            if (settled.equals(financingAfter) && "MORTGAGE_SETUP_PENDING".equals(property.getFinancingStatus())) {
                property.setFinancingStatus(settled);
                savePropertyVersioned(property);
            }
            return getProperty(portfolioId, propertyId);
        }
        Mortgage loan = found.get();
        version(loan.getVersion(), expectedVersion);
        active(property);
        boolean wasActive = "ACTIVE".equals(loan.getStatus());
        boolean othersOpen = getMortgages(portfolioId, propertyId).stream()
                .anyMatch(m -> !m.getId().equals(loan.getId()) && "ACTIVE".equals(m.getStatus()));
        String before = property.getFinancingStatus(), after = before;
        if (wasActive) {
            // With another loan still open the property cannot be mortgage free: the caller says
            // whether the remaining loans are all of its debt or a corrected loan follows.
            String settled = othersOpen ? "MORTGAGE_RECORDED" : "MORTGAGE_FREE";
            if (!settled.equals(financingAfter) && !"MORTGAGE_SETUP_PENDING".equals(financingAfter)) {
                throw fail(400, othersOpen ? "Another loan is still open: choose whether the remaining loans are all of the debt or a corrected loan follows"
                        : "Choose whether the property is mortgage free or needs a corrected mortgage");
            }
            after = financingAfter;
        } else if ("MORTGAGE_RECORDED".equals(after) && getMortgages(portfolioId, propertyId).size() == 1) {
            after = "MORTGAGE_FREE"; // the only loan was closed on a confirmed zero balance
        }
        // Debt reads unknown while the active loan is removed, so a failure part-way never
        // shows the property as unencumbered.
        if (wasActive && !"MORTGAGE_SETUP_PENDING".equals(property.getFinancingStatus())) {
            property.setFinancingStatus("MORTGAGE_SETUP_PENDING");
            savePropertyVersioned(property);
        }
        try { mortgageRepository.delete(loan); }
        catch (OptimisticLockingFailureException e) {
            RealEstateProperty current = requireProperty(portfolioId, propertyId);
            if (wasActive && !before.equals(current.getFinancingStatus())) {
                current.setFinancingStatus(before);
                savePropertyVersioned(current);
            }
            throw fail(409, "Mortgage changed elsewhere; reload before deleting");
        }
        RealEstateProperty current = requireProperty(portfolioId, propertyId);
        if (!after.equals(current.getFinancingStatus())) {
            current.setFinancingStatus(after);
            savePropertyVersioned(current);
        }
        return getProperty(portfolioId, propertyId);
    }

    public List<MortgageCalculationService.Installment> schedule(String portfolioId, String propertyId, String mortgageId,
                                                                 LocalDate from, LocalDate to) {
        Mortgage loan = requireMortgage(portfolioId, propertyId, mortgageId);
        LocalDate start = from == null ? loan.getBalanceCheckpoints().stream().map(Mortgage.BalanceCheckpoint::getAsOfDate)
                .min(LocalDate::compareTo).orElse(loan.getLoanStartDate()) : from;
        LocalDate end = to == null ? today().plusYears(1) : to;
        if (end.isBefore(start) || java.time.temporal.ChronoUnit.MONTHS.between(start, end) > 720) {
            throw fail(400, "Choose a schedule range of at most 60 years");
        }
        return calculations.schedule(loan, start, end, today());
    }

    public Preview preview(String portfolioId, String propertyId, String mortgageId, PreviewCommand command) {
        Mortgage loan = requireMortgage(portfolioId, propertyId, mortgageId);
        LocalDate effective = required(command.effectiveFrom(), "Effective date");
        if (effective.isBefore(loan.getLoanStartDate()) || effective.isAfter(loan.getMaturityDate())) {
            throw fail(400, "Scenario effective date must be within the mortgage term");
        }
        Mortgage scenario = copyLoan(loan);
        if (command.annualNominalRatePercent() != null) {
            var value = rateValue(scenario, new RateCommand(null, effective,
                    command.fixedUntil() == null ? scenario.getMaturityDate() : command.fixedUntil(),
                    command.annualNominalRatePercent(), command.monthlyPayment(),
                    command.monthlyPayment() == null ? "CALCULATED" : "BANK_AGREED"), null);
            scenario.getRatePeriods().removeIf(r -> !r.getEffectiveFrom().isBefore(effective));
            for (var prior : scenario.getRatePeriods()) if (!prior.getFixedUntil().isBefore(effective)) prior.setFixedUntil(effective.minusDays(1));
            scenario.getRatePeriods().add(value);
        }
        if (command.extraPrincipalPaid() != null && command.extraPrincipalPaid().signum() > 0) {
            var balance = calculations.balanceAt(scenario, effective);
            if (balance.principalBalance() == null || command.extraPrincipalPaid().compareTo(balance.principalBalance()) > 0) {
                throw fail(400, "Extra principal exceeds the available principal balance");
            }
            scenario.getPayments().add(new Mortgage.Payment("preview", effective, null, ZERO,
                    money(command.extraPrincipalPaid(), "Extra principal", false), ZERO, ZERO, "Unsaved scenario", now(), null, null));
        }
        return new Preview(calculations.summarizeScenario(scenario, effective),
                calculations.scheduleScenario(scenario, effective, scenario.getMaturityDate(), today()));
    }

    /**
     * What an extra principal payment would do, saved nowhere. Past the last agreed fixation the
     * latest rate is assumed to continue (reported), or no payoff date could be projected. A bank
     * balance includes its day's payments and replaces the principal, so a payment on or before
     * the last one is placed the day after it.
     */
    public ExtraPaymentPreview extraPaymentPreview(String portfolioId, String propertyId, String mortgageId, ExtraPaymentCommand command) {
        Mortgage loan = requireMortgage(portfolioId, propertyId, mortgageId);
        if (!"ACTIVE".equals(loan.getStatus())) throw fail(409, "A closed mortgage has nothing left to repay");
        LocalDate date = command.date() == null ? today() : command.date();
        LocalDate lastBalance = loan.getBalanceCheckpoints().stream().map(Mortgage.BalanceCheckpoint::getAsOfDate)
                .max(LocalDate::compareTo).orElse(date.minusDays(1));
        LocalDate effective = date.isAfter(lastBalance) ? date : lastBalance.plusDays(1);
        if (date.isBefore(loan.getLoanStartDate()) || !effective.isBefore(loan.getMaturityDate())) {
            throw fail(400, "Choose a date within the loan term");
        }
        BigDecimal amount = money(command.amount(), "Extra principal", false);
        Mortgage base = copyLoan(loan);
        Mortgage.RatePeriod latest = base.getRatePeriods().stream().max(Comparator.comparing(Mortgage.RatePeriod::getFixedUntil)).orElse(null);
        if (latest == null || latest.getFixedUntil().isBefore(effective)) throw fail(409, "Record the rate that applies on that date first");
        LocalDate assumedAfter = latest.getFixedUntil().isBefore(base.getMaturityDate()) ? latest.getFixedUntil() : null;
        if (assumedAfter != null) latest.setFixedUntil(base.getMaturityDate());
        BigDecimal balance = calculations.balanceAt(base, effective).principalBalance();
        if (balance == null || amount.compareTo(balance) > 0) throw fail(400, "Extra principal exceeds the balance on that date");
        Mortgage keep = copyLoan(base), lower = copyLoan(base);
        keep.getPayments().add(new Mortgage.Payment("preview-keep", effective, null, ZERO, amount, ZERO, ZERO, "Unsaved scenario", now(), null, null));
        lower.getPayments().add(new Mortgage.Payment("preview-lower", effective, null, ZERO, amount, ZERO, ZERO, "Unsaved scenario", now(), true, null));
        rebaseLaterRates(lower, effective, day -> calculations.summarizeScenario(lower, day).balance().principalBalance());
        return new ExtraPaymentPreview(date, amount, outcome(base, effective), outcome(keep, effective), outcome(lower, effective),
                assumedAfter == null ? null : latest.getAnnualNominalRatePercent(), assumedAfter);
    }

    private ExtraPaymentOutcome outcome(Mortgage scenario, LocalDate effective) {
        var rows = calculations.scheduleScenario(scenario, today(), scenario.getMaturityDate(), today());
        var installments = rows.stream().filter(r -> "INSTALLMENT".equals(r.kind()) && r.total() != null).toList();
        BigDecimal installment = installments.stream().filter(r -> r.date().isAfter(effective))
                .map(MortgageCalculationService.Installment::total).findFirst().orElse(null);
        LocalDate payoff = rows.stream().filter(r -> ("INSTALLMENT".equals(r.kind()) || "PAYMENT".equals(r.kind()))
                && r.balance() != null && r.balance().signum() == 0).map(MortgageCalculationService.Installment::date).findFirst().orElse(null);
        BigDecimal interest = installments.stream().filter(r -> r.date().isAfter(today()))
                .map(MortgageCalculationService.Installment::interest).reduce(ZERO, BigDecimal::add);
        int left = (int) installments.stream().filter(r -> r.date().isAfter(effective) && r.principal().signum() > 0).count();
        return new ExtraPaymentOutcome(installment, payoff, interest, left);
    }

    private PropertyView mutateLoan(String portfolioId, String propertyId, String mortgageId, Long expectedVersion,
                                   String operation, Object command, String key, Consumer<Mortgage> mutation) {
        Mortgage loan = requireMortgage(portfolioId, propertyId, mortgageId);
        String receiptKey = operation + ":" + requestKey(key);
        if (replayed(loan.getRetryReceipts(), receiptKey, command)) return getProperty(portfolioId, propertyId);
        version(loan.getVersion(), expectedVersion);
        boolean correction = !operation.endsWith(":new") && !"close".equals(operation);
        if (!"ACTIVE".equals(loan.getStatus()) && !correction) throw fail(409, "A closed mortgage only accepts corrections to existing history");
        mutation.accept(loan);
        if (loan.getCloseDate() != null && loan.getBalanceCheckpoints().stream().noneMatch(c ->
                c.getAsOfDate().equals(loan.getCloseDate()) && c.getPrincipalBalance().signum() == 0)) {
            throw fail(409, "A closed mortgage must retain its confirmed zero closing balance");
        }
        save(loan, receiptKey, command);
        return getProperty(portfolioId, propertyId);
    }

    private void applyProperty(RealEstateProperty property, PropertyCommand command, boolean creating) {
        property.setName(text(command.name(), "Property name", 120));
        property.setPropertyType(choice(command.propertyType(), PROPERTY_TYPES, "Property type", "OTHER"));
        property.setUsage(choice(command.usage(), USAGES, "Usage", "OTHER"));
        String currency = currency(command.currency());
        if (!creating && !property.getCurrency().equals(currency)) throw fail(409, "Currency cannot change after tracking has started");
        property.setCurrency(currency);
        BigDecimal share = share(command.ownershipShare(), "Ownership share", false);
        if (!creating && property.getOwnershipShare().compareTo(share) != 0) throw fail(409, "Changing ownership requires an effective-dated transfer");
        property.setOwnershipShare(share);
        LocalDate start = required(command.trackingStartDate(), "Tracking start date");
        validActualDate(start, "Tracking start date");
        if (!creating && !start.equals(property.getTrackingStartDate())) throw fail(409, "Tracking start cannot change after records exist");
        property.setTrackingStartDate(start);
        if (command.purchaseDate() != null) validActualDate(command.purchaseDate(), "Purchase date");
        property.setPurchaseDate(command.purchaseDate());
        property.setPurchasePrice(command.purchasePrice() == null ? null : money(command.purchasePrice(), "Purchase price", false));
        property.setAcquisitionCosts(command.acquisitionCosts() == null ? null : money(command.acquisitionCosts(), "Acquisition costs", false));
        String financing = choice(command.financingStatus(), FINANCING, "Financing status", "MORTGAGE_FREE");
        // Pending → recorded is the caller confirming every loan is recorded (checked against the
        // open loans by updateProperty); mortgage free → recorded still needs mortgage setup.
        if (!creating && "MORTGAGE_RECORDED".equals(financing) && "MORTGAGE_FREE".equals(property.getFinancingStatus())) {
            throw fail(400, "Add the mortgage through mortgage setup");
        }
        property.setFinancingStatus(financing);
        property.setCountry(optional(command.country(), 80));
        property.setLocation(optional(command.location(), 200));
        property.setNotes(optional(command.notes(), 5000));
    }

    private void applyMortgage(Mortgage loan, RealEstateProperty property, MortgageCommand command, boolean creating) {
        String currency = currency(command.currency() == null ? property.getCurrency() : command.currency());
        if (!currency.equals(property.getCurrency())) throw fail(400, "Property and mortgage currency must match");
        loan.setCurrency(currency);
        loan.setName(optional(command.name(), 120));
        loan.setLender(optional(command.lender(), 120));
        loan.setOriginalPrincipal(command.originalPrincipal() == null ? null : money(command.originalPrincipal(), "Original principal", false));
        LocalDate start = required(command.loanStartDate(), "Loan start date");
        validActualDate(start, "Loan start date");
        LocalDate first = required(command.firstPaymentDate(), "First payment date");
        LocalDate maturity = required(command.maturityDate(), "Maturity date");
        if (first.isBefore(start) || !maturity.isAfter(start) || maturity.isBefore(first) || maturity.isAfter(start.plusYears(60))) {
            throw fail(400, "Payment and maturity dates must follow loan start, within 60 years");
        }
        Integer day = required(command.paymentDay(), "Payment day");
        if (day < 1 || day > 31 || first.getDayOfMonth() != Math.min(day, first.lengthOfMonth())) throw fail(400, "First payment date must match payment day (1–31)");
        BigDecimal allocation = share(command.debtAllocationShare(), "Debt allocation share", true);
        if (!creating && (loan.getDebtAllocationShare().compareTo(allocation) != 0
                || !loan.getLoanStartDate().equals(start) || !loan.getFirstPaymentDate().equals(first)
                || !loan.getPaymentDay().equals(day) || !loan.getMaturityDate().equals(maturity))) {
            throw fail(409, "Changing the loan timeline or debt allocation requires an explicit transfer/refinance; record a rate period to change the rate");
        }
        loan.setLoanStartDate(start);
        loan.setFirstPaymentDate(first);
        loan.setMaturityDate(maturity);
        loan.setPaymentDay(day);
        loan.setDebtAllocationShare(allocation);
        loan.setBalanceTrackingMode(choice(command.balanceTrackingMode(), Set.of("STATEMENT_ONLY", "SCHEDULE_ESTIMATE"),
                "Balance tracking mode", "SCHEDULE_ESTIMATE"));
    }

    private Mortgage.BalanceCheckpoint balanceValue(Mortgage loan, BalanceCommand command, String id) {
        validLoanActualDate(loan, command.asOfDate(), "Bank balance date");
        return new Mortgage.BalanceCheckpoint(id == null ? UUID.randomUUID().toString() : id, command.asOfDate(),
                money(command.principalBalance(), "Principal balance", false),
                choice(command.source(), Set.of("BANK_STATEMENT", "USER_CONFIRMED"), "Balance source", "BANK_STATEMENT"),
                optional(command.note(), 2000), now());
    }

    private Mortgage.RatePeriod rateValue(Mortgage loan, RateCommand command, String id) {
        return rateValue(loan, command, id, date -> calculations.balanceAt(loan, date).principalBalance());
    }

    private Mortgage.RatePeriod rateValue(Mortgage loan, RateCommand command, String id, Function<LocalDate, BigDecimal> balanceOn) {
        LocalDate from = required(command.effectiveFrom(), "Rate effective date");
        LocalDate until = required(command.fixedUntil(), "Fixed-through date");
        if (from.isBefore(loan.getLoanStartDate()) || until.isBefore(from) || from.isAfter(loan.getMaturityDate()) || until.isAfter(loan.getMaturityDate())) {
            throw fail(400, "Rate period must be within the mortgage term and fixed-through must follow its start");
        }
        BigDecimal rate = money(command.annualNominalRatePercent(), "Annual nominal rate", false);
        if (rate.compareTo(new BigDecimal("100")) > 0) throw fail(400, "Annual nominal rate cannot exceed 100%");
        BigDecimal payment = command.monthlyPayment() == null ? null : money(command.monthlyPayment(), "Monthly installment", false);
        String source = payment == null ? "CALCULATED" : choice(command.paymentSource(), Set.of("BANK_AGREED", "CALCULATED"), "Payment source", "BANK_AGREED");
        var anchor = loan.getBalanceCheckpoints().stream().filter(c -> !c.getAsOfDate().isBefore(from) && !c.getAsOfDate().isAfter(until))
                .min(Comparator.comparing(Mortgage.BalanceCheckpoint::getAsOfDate)).orElse(null);
        LocalDate anchorDate = anchor == null ? from : anchor.getAsOfDate();
        BigDecimal principal = anchor == null ? balanceOn.apply(anchorDate) : anchor.getPrincipalBalance();
        int count = calculations.remainingPaymentCount(loan, anchorDate);
        // Principal does not move between installments, so a mid-month anchor gives the same annuity.
        if (payment == null && principal != null && principal.signum() > 0 && count > 0) {
            payment = calculations.monthlyPayment(principal, rate, count,
                    Math.max(0, Currency.getInstance(loan.getCurrency()).getDefaultFractionDigits()));
        }
        if (payment != null && principal != null && principal.signum() > 0
                && payment.compareTo(principal.multiply(rate).divide(new BigDecimal("1200"), 12, RoundingMode.HALF_UP)) <= 0) {
            throw fail(400, "Monthly installment must exceed interest to repay this loan");
        }
        return new Mortgage.RatePeriod(id == null ? UUID.randomUUID().toString() : id, from, until, rate, payment,
                payment == null ? null : source, anchorDate, principal, count, "USER_CONFIRMED", now());
    }

    /**
     * A lowered installment has to survive the next refixation. A later CALCULATED rate was worked
     * out on the balance before the extra payment, so it would end the loan early again: work it
     * out once more, in date order, on what is left. Bank-agreed installments are the bank's.
     */
    private void rebaseLaterRates(Mortgage loan, LocalDate after, Function<LocalDate, BigDecimal> balanceOn) {
        var later = loan.getRatePeriods().stream()
                .filter(p -> "CALCULATED".equals(p.getPaymentSource()) && p.getEffectiveFrom().isAfter(after))
                .sorted(Comparator.comparing(Mortgage.RatePeriod::getEffectiveFrom)).toList();
        for (var period : later) {
            var value = rateValue(loan, new RateCommand(null, period.getEffectiveFrom(), period.getFixedUntil(),
                    period.getAnnualNominalRatePercent(), null, "CALCULATED"), period.getId(), balanceOn);
            if (value.getMonthlyPayment() == null) continue; // balance unknown there: keep what was stored
            value.setRecordedAt(period.getRecordedAt());
            loan.getRatePeriods().replaceAll(p -> p == period ? value : p);
        }
    }

    private void recordFinancing(RealEstateProperty property) {
        if (!"MORTGAGE_RECORDED".equals(property.getFinancingStatus())) {
            property.setFinancingStatus("MORTGAGE_RECORDED");
            savePropertyVersioned(property);
        }
    }

    private void save(RealEstateProperty property, String key, Object command) {
        receipt(property.getRetryReceipts(), key, command);
        savePropertyVersioned(property);
    }

    private void savePropertyVersioned(RealEstateProperty property) {
        property.setUpdatedAt(now());
        try { propertyRepository.save(property); }
        catch (OptimisticLockingFailureException e) { throw fail(409, "Property changed elsewhere; reload before saving again"); }
    }

    private void save(Mortgage loan, String key, Object command) {
        receipt(loan.getRetryReceipts(), key, command);
        loan.setUpdatedAt(now());
        try { mortgageRepository.save(loan); }
        catch (OptimisticLockingFailureException e) { throw fail(409, "Mortgage changed elsewhere; reload before saving again"); }
        catch (DuplicateKeyException e) { throw fail(409, LEGACY_INDEX_MESSAGE); }
    }

    private static boolean replayed(List<RealEstateProperty.RetryReceipt> receipts, String key, Object command) {
        for (var receipt : receipts) if (key.equals(receipt.getKey())) {
            sameFingerprint(receipt.getFingerprint(), command);
            return true;
        }
        return false;
    }

    private static void receipt(List<RealEstateProperty.RetryReceipt> receipts, String key, Object command) {
        if (receipts.size() >= MAX_EVENTS * 3) throw fail(400, "Command history limit reached");
        receipts.add(new RealEstateProperty.RetryReceipt(key, fingerprint(command)));
    }

    private static void sameFingerprint(String expected, Object command) {
        if (Objects.equals(expected, fingerprint(command))) return;
        // Fingerprints written before normalization must still accept an unchanged retry.
        if (expected != null && !expected.startsWith("v2:") && expected.equals(hash(command.toString()))) return;
        throw fail(409, "This retry key was already used with different input");
    }

    private static void version(Long actual, Long expected) {
        if (expected == null || !Objects.equals(actual, expected)) throw fail(409, "This record changed; reload before saving again");
    }

    private static String requestKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw fail(400, "A valid Idempotency-Key is required");
        return key;
    }

    private static String stableId(String portfolioId, String operation, String key) {
        return UUID.nameUUIDFromBytes((portfolioId + ":" + operation + ":" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String fingerprint(Object command) {
        return "v2:" + hash(canonical(command));
    }

    /** The loan's own input: the version fields change when the parent is marked pending, so they are left out. */
    private static String setupFingerprint(MortgageCommand c) {
        return fingerprint(new MortgageCommand(null, null, c.name(), c.lender(), c.currency(), c.originalPrincipal(),
                c.loanStartDate(), c.firstPaymentDate(), c.maturityDate(), c.paymentDay(), c.debtAllocationShare(),
                c.balanceTrackingMode(), c.openingBalance(), c.ratePeriod()));
    }

    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof BigDecimal decimal) return "decimal:" + decimal.stripTrailingZeros();
        if (value.getClass().isRecord()) {
            StringBuilder result = new StringBuilder(value.getClass().getName()).append('[');
            for (var component : value.getClass().getRecordComponents()) {
                try {
                    result.append(component.getName()).append('=').append(canonical(component.getAccessor().invoke(value))).append(';');
                } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot fingerprint command", e); }
            }
            return result.append(']').toString();
        }
        String text = value.toString();
        return text.length() + ":" + text;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String text(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw fail(400, label + " is required (max " + max + " characters)");
        return value.trim();
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > max) throw fail(400, "Text exceeds " + max + " characters");
        return value.trim();
    }

    private static String choice(String value, Set<String> allowed, String label, String fallback) {
        String result = value == null ? fallback : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(result)) throw fail(400, "Invalid " + label.toLowerCase(Locale.ROOT));
        return result;
    }

    private static BigDecimal money(BigDecimal value, String label, boolean emptyAsZero) {
        if (value == null && emptyAsZero) return ZERO;
        if (value == null || value.signum() < 0 || value.precision() > 24 || value.scale() > 12
                || value.abs().compareTo(new BigDecimal("1000000000000000")) > 0) throw fail(400, label + " must be a nonnegative finite amount");
        return value;
    }

    private static BigDecimal share(BigDecimal value, String label, boolean allowZero) {
        BigDecimal result = value == null ? BigDecimal.ONE : value;
        if (result.scale() > 12 || result.precision() > 16 || result.compareTo(BigDecimal.ONE) > 0
                || (allowZero ? result.signum() < 0 : result.signum() <= 0)) throw fail(400, label + " must be between " + (allowZero ? "0" : "greater than 0") + " and 1 (up to 12 decimals)");
        return result;
    }

    private static String currency(String value) {
        String code = text(value, "Currency", 3).toUpperCase(Locale.ROOT);
        try { Currency.getInstance(code); }
        catch (IllegalArgumentException e) { throw fail(400, "Currency must be a supported three-letter code"); }
        return code;
    }

    private void validActualDate(LocalDate value, String label) {
        required(value, label);
        if (value.isAfter(today()) || value.isBefore(LocalDate.of(1900, 1, 1))) throw fail(400, label + " must be between 1900 and today");
    }

    private void validLoanActualDate(Mortgage loan, LocalDate value, String label) {
        validActualDate(value, label);
        if (value.isBefore(loan.getLoanStartDate())) throw fail(400, label + " cannot precede loan start");
        if (loan.getCloseDate() != null && value.isAfter(loan.getCloseDate())) throw fail(400, label + " cannot follow mortgage closure");
    }

    private static boolean dueOn(Mortgage loan, LocalDate date) {
        return !date.isBefore(loan.getFirstPaymentDate()) && !date.isAfter(loan.getMaturityDate())
                && date.getDayOfMonth() == Math.min(loan.getPaymentDay(), date.lengthOfMonth());
    }

    private static void active(RealEstateProperty property) {
        if (!"ACTIVE".equals(property.getStatus())) throw fail(409, "This property is archived");
    }

    private static <T> T required(T value, String label) {
        if (value == null) throw fail(400, label + " is required");
        return value;
    }

    private LocalDate today() { return LocalDate.now(clock); }
    private Instant now() { return clock.instant(); }
    private static RealEstateException fail(int status, String message) { return new RealEstateException(status, message); }

    private static Mortgage copyLoan(Mortgage source) {
        Mortgage copy = new Mortgage();
        org.springframework.beans.BeanUtils.copyProperties(source, copy);
        copy.setPayments(new ArrayList<>(source.getPayments()));
        copy.setBalanceCheckpoints(new ArrayList<>(source.getBalanceCheckpoints()));
        copy.setRatePeriods(source.getRatePeriods().stream().map(r -> {
            Mortgage.RatePeriod cloned = new Mortgage.RatePeriod();
            org.springframework.beans.BeanUtils.copyProperties(r, cloned);
            return cloned;
        }).collect(Collectors.toCollection(ArrayList::new)));
        return copy;
    }
}
