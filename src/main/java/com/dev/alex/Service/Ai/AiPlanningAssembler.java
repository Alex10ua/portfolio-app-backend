package com.dev.alex.Service.Ai;

import com.dev.alex.Model.CustomAsset;
import com.dev.alex.Model.DividendReceipt;
import com.dev.alex.Model.Enums.DividendReceiptStatus;
import com.dev.alex.Model.NonDbModel.Ai.AiCustomAsset;
import com.dev.alex.Model.NonDbModel.Ai.AiDividendPayments;
import com.dev.alex.Model.NonDbModel.Ai.AiSelfFunding;
import com.dev.alex.Model.NonDbModel.Ai.AiTickerNote;
import com.dev.alex.Model.NonDbModel.DividendsCalendarData;
import com.dev.alex.Model.NonDbModel.HoldingsCompleteData;
import com.dev.alex.Model.NonDbModel.PriceHistoryEntry;
import com.dev.alex.Model.TickerNotes;
import com.dev.alex.Model.TickerTags;
import com.dev.alex.Service.CustomAssetServiceImpl;
import com.dev.alex.Service.DividendCalendarServiceImpl;
import com.dev.alex.Service.DividendReceiptServiceImpl;
import com.dev.alex.Service.HoldingsCompleteDataServiceImpl;
import com.dev.alex.Service.NotesServiceImpl;
import com.dev.alex.Service.TagServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Reuses stored data and the SPA's small planning calculations without writing or converting FX. */
@Service
public class AiPlanningAssembler {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Autowired private DividendCalendarServiceImpl calendarService;
    @Autowired private DividendReceiptServiceImpl receiptService;
    @Autowired private HoldingsCompleteDataServiceImpl holdingsService;
    @Autowired private TagServiceImpl tagService;
    @Autowired private NotesServiceImpl notesService;
    @Autowired private CustomAssetServiceImpl customAssetService;

    public AiDividendPayments dividendPayments(String portfolioId, int year, String status) {
        return dividendPayments(portfolioId, year, status, LocalDate.now());
    }

    AiDividendPayments dividendPayments(String portfolioId, int year, String status, LocalDate today) {
        if (year < 1 || year > 9998) throw badRequest("year must be between 1 and 9998");
        String filter = normalize(status, "ALL");
        if (!Set.of("ALL", "UPCOMING", "AWAITING", "RECEIVED", "MISSING").contains(filter)) {
            throw badRequest("status must be ALL, UPCOMING, AWAITING, RECEIVED or MISSING");
        }
        Map<String, List<DividendsCalendarData>> calendar = calendarService
                .getDividendCalendarByPortfolioId(portfolioId, year);
        List<DividendsCalendarData> rows = flatten(calendar);
        List<DividendReceipt> receipts = receiptService.getReceipts(portfolioId);
        if (receipts == null) receipts = List.of();
        Set<String> claimedDates = new HashSet<>();
        for (DividendsCalendarData row : rows) {
            if (row.getExDate() != null) claimedDates.add(receiptKey(row.getTicker(), row.getExDate()));
        }
        List<AiDividendPayments.Payment> payments = new ArrayList<>();
        Map<String, Long> counts = new TreeMap<>();
        for (String key : List.of("UPCOMING", "AWAITING", "RECEIVED", "MISSING", "UNKNOWN")) counts.put(key, 0L);
        for (DividendsCalendarData row : rows) {
            DividendReceipt receipt = matchReceipt(row, receipts, claimedDates, year);
            // A projected override follows one payment only, even when two nearby
            // events fall inside the fourteen-day matching window.
            if (receipt != null && receipt.getExDate() != null) claimedDates.add(receiptKey(receipt.getTicker(), receipt.getExDate()));
            AiDividendPayments.Payment payment = payment(row, receipt, today);
            counts.merge(payment.status(), 1L, Long::sum);
            if ("ALL".equals(filter) || filter.equals(payment.status())) payments.add(payment);
        }
        payments.sort(Comparator.comparing((AiDividendPayments.Payment p) ->
                        p.payDate() == null ? p.exDate() : p.payDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AiDividendPayments.Payment::ticker, Comparator.nullsLast(Comparator.naturalOrder())));
        return new AiDividendPayments(year, today, filter, counts, payments);
    }

    /** The UI's grace window is a tracking assumption, never evidence that cash arrived. */
    static AiDividendPayments.Payment payment(DividendsCalendarData row, DividendReceipt receipt, LocalDate today) {
        LocalDate anchor = row.getPayDate() == null ? row.getExDate() : row.getPayDate();
        String natural = "UNKNOWN";
        if (anchor != null) {
            natural = anchor.isAfter(today) ? "UPCOMING"
                    : row.isScheduled() || today.isBefore(anchor.plusDays(row.getPayDate() == null ? 30 : 3))
                    ? "AWAITING" : "RECEIVED";
        }
        DividendReceiptStatus override = receipt == null ? null : receipt.getStatus();
        BigDecimal rate = receipt == null ? null : receipt.getWithholdingRate();
        BigDecimal gross = row.getDividendAmount() == null || row.getStockQuantity() == null ? null
                : row.getDividendAmount().multiply(row.getStockQuantity());
        BigDecimal withheld = gross == null || rate == null ? null
                : gross.multiply(rate).divide(HUNDRED, PRECISION);
        return new AiDividendPayments.Payment(row.getTicker(), row.getName(), row.getCurrency(), row.getCountry(),
                row.getExDate(), row.getPayDate(), row.isPayDateEstimated(), row.isScheduled(),
                row.getDividendAmount(), row.getStockQuantity(), gross, row.getPaymentsPerYear(),
                natural, override == null ? natural : override.name(),
                override == null ? "DATE_RULE" : "USER_OVERRIDE", override == null ? null : override.name(),
                receipt == null ? null : receipt.getExDate(), override == DividendReceiptStatus.RECEIVED,
                rate, rate == null ? "NOT_RECORDED" : "USER_OVERRIDE", withheld,
                withheld == null ? null : gross.subtract(withheld));
    }

    /** Matches the SPA's exact key, then an otherwise unmatched projected date within fourteen days. */
    static DividendReceipt matchReceipt(DividendsCalendarData row, List<DividendReceipt> receipts,
            Set<String> claimedDates, int year) {
        if (row.getExDate() == null) return null;
        DividendReceipt nearest = null;
        long nearestDays = 15;
        for (DividendReceipt receipt : receipts) {
            if (receipt == null || !Objects.equals(row.getTicker(), receipt.getTicker()) || receipt.getExDate() == null) continue;
            if (row.getExDate().equals(receipt.getExDate())) return receipt;
            if (receipt.getExDate().getYear() != year || claimedDates.contains(receiptKey(receipt.getTicker(), receipt.getExDate()))) continue;
            long days = Math.abs(ChronoUnit.DAYS.between(receipt.getExDate(), row.getExDate()));
            if (days < nearestDays) { nearestDays = days; nearest = receipt; }
        }
        return nearest;
    }

    public AiTickerNote note(String username, String portfolioId, String ticker) {
        TickerNotes note = notesService.getNoteForTicker(username, portfolioId, ticker.trim().toUpperCase(Locale.ROOT));
        return new AiTickerNote(portfolioId, note.getTicker(), note.getNote(), note.getId() == null ? null : note.getUpdatedAt());
    }

    public List<AiCustomAsset> customAssets(String portfolioId, int historyPoints) {
        List<CustomAsset> raw = customAssetService.findAllByPortfolioId(portfolioId);
        return raw == null ? List.of() : raw.stream().filter(Objects::nonNull)
                .map(asset -> customAsset(asset, Math.clamp(historyPoints, 0, 120)))
                .sorted(Comparator.comparing(AiCustomAsset::ticker, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public AiCustomAsset customAsset(String portfolioId, String ticker, int historyPoints) {
        return customAssetService.findOptionalByPortfolioIdAndTicker(portfolioId, ticker)
                .map(asset -> customAsset(asset, Math.clamp(historyPoints, 0, 600))).orElse(null);
    }

    static AiCustomAsset customAsset(CustomAsset asset, int historyPoints) {
        List<PriceHistoryEntry> history = asset.getPriceHistory() == null ? List.of() : asset.getPriceHistory().stream()
                .filter(Objects::nonNull).sorted(Comparator.comparing(PriceHistoryEntry::getDate,
                        Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        int first = Math.max(0, history.size() - Math.max(0, historyPoints));
        List<PriceHistoryEntry> selected = history.subList(first, history.size());
        return new AiCustomAsset(asset.getId(), asset.getPortfolioId(), asset.getTicker(), asset.getName(),
                asset.getAssetType(), asset.getDescription(), asset.getCountry(), asset.getCurrency(), asset.getUnit(),
                asset.getPriceNow(), selected, history.size(), selected.size() < history.size(),
                asset.getPriceUpdateMethod(), asset.getCustomFields(), asset.getCreatedAt(), asset.getUpdatedAt());
    }

    public AiSelfFunding selfFunding(String portfolioId, String username, String period) {
        String selectedPeriod = normalize(period, "YEARLY");
        int divisor = switch (selectedPeriod) {
            case "YEARLY" -> 1;
            case "QUARTERLY" -> 4;
            case "MONTHLY" -> 12;
            default -> throw badRequest("period must be YEARLY, QUARTERLY or MONTHLY");
        };
        Map<String, Set<String>> payingMonths = new HashMap<>();
        Map<String, List<DividendsCalendarData>> calendar = calendarService.getDividendCalendarByPortfolioId(portfolioId);
        if (calendar != null) calendar.forEach((month, entries) -> {
            if (entries == null) return;
            for (DividendsCalendarData row : entries) {
                if (row != null && row.getTicker() != null) payingMonths
                        .computeIfAbsent(row.getTicker().toUpperCase(Locale.ROOT), ignored -> new HashSet<>()).add(month);
            }
        });
        Map<String, List<String>> tags = new HashMap<>();
        List<TickerTags> rawTags = tagService.getAllTagsForUser(username, portfolioId);
        if (rawTags != null) for (TickerTags tag : rawTags) {
            if (tag != null && tag.getTicker() != null && tag.getTags() != null) tags.put(tag.getTicker(), tag.getTags());
        }
        List<HoldingsCompleteData> holdings = holdingsService.getAllHoldingsByPortfolioId(portfolioId);
        List<AiSelfFunding.Row> rows = new ArrayList<>();
        int excluded = 0;
        if (holdings != null) for (HoldingsCompleteData holding : holdings) {
            if (holding == null || !positive(holding.getShareAmount())) continue;
            Integer cadence = holding.getTicker() == null ? null : payingMonths.containsKey(holding.getTicker().toUpperCase(Locale.ROOT))
                    ? payingMonths.get(holding.getTicker().toUpperCase(Locale.ROOT)).size() : null;
            AiSelfFunding.Row row = selfFundingRow(holding, divisor, cadence, tags.getOrDefault(holding.getTicker(), List.of()));
            if (row == null) excluded++; else rows.add(row);
        }
        rows.sort(Comparator.comparing(AiSelfFunding.Row::ticker, Comparator.nullsLast(Comparator.naturalOrder())));
        return new AiSelfFunding(selectedPeriod, LocalDate.now(), excluded, rows);
    }

    static AiSelfFunding.Row selfFundingRow(HoldingsCompleteData holding, int divisor, Integer cadence, List<String> tags) {
        BigDecimal price = holding.getCurrentShareValue(), dividend = holding.getDividend(), held = holding.getShareAmount();
        if (!positive(price) || !positive(dividend) || !positive(held)) return null;
        BigDecimal perPeriod = dividend.divide(BigDecimal.valueOf(divisor), PRECISION);
        BigDecimal needed = price.multiply(BigDecimal.valueOf(divisor)).divide(dividend, 0, RoundingMode.CEILING).max(BigDecimal.ONE);
        BigDecimal gap = needed.subtract(held).max(BigDecimal.ZERO);
        BigDecimal yield = dividend.divide(price, PRECISION);
        BigDecimal coverage = held.multiply(perPeriod).divide(price, PRECISION);
        BigDecimal ratio = gap.divide(needed, PRECISION);
        String tier = gap.signum() == 0 ? "SELF_FUNDING" : ratio.compareTo(new BigDecimal("0.25")) < 0 ? "CLOSE"
                : ratio.compareTo(new BigDecimal("0.6")) < 0 ? "FAR" : "VERY_FAR";
        BigDecimal years = BigDecimal.ZERO;
        if (gap.signum() > 0) {
            double estimate = Math.log(needed.divide(held, PRECISION).doubleValue()) / Math.log1p(yield.doubleValue());
            years = Double.isFinite(estimate) ? BigDecimal.valueOf(estimate) : null;
        }
        String currency = holding.getQuoteCurrency() == null ? holding.getCurrency() : holding.getQuoteCurrency();
        return new AiSelfFunding.Row(holding.getTicker(), holding.getName(), holding.getAssetType(), currency,
                price, dividend, held, cadence, tags, perPeriod,
                cadence == null || cadence <= 0 ? null : dividend.divide(BigDecimal.valueOf(cadence), PRECISION),
                needed, gap, gap.multiply(price), coverage, coverage.multiply(HUNDRED), yield.multiply(HUNDRED), years, tier);
    }

    private static List<DividendsCalendarData> flatten(Map<String, List<DividendsCalendarData>> calendar) {
        if (calendar == null) return List.of();
        return calendar.values().stream().filter(Objects::nonNull).flatMap(List::stream).filter(Objects::nonNull)
                .sorted(Comparator.comparing(DividendsCalendarData::getExDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }
    private static String receiptKey(String ticker, LocalDate date) { return ticker + "|" + date; }
    private static IllegalArgumentException badRequest(String message) { return new IllegalArgumentException(message); }
}
