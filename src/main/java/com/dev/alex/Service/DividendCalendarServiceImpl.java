package com.dev.alex.Service;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.DividendsCalendarData;
import com.dev.alex.Model.NonDbModel.MarketStatistics;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Service.Ai.AiTickerAssembler;
import com.dev.alex.Service.Dividends.DividendUtils;
import com.dev.alex.Service.Interface.DividendCalendarService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class DividendCalendarServiceImpl implements DividendCalendarService {
        @Autowired
        private HoldingServiceImpl holdingService;
        @Autowired
        private MarketDataServiceImpl marketDataService;
        @Autowired
        private TransactionServiceImpl transactionService;
        @Autowired
        private DividendUtils dividendUtils;

        @Override
        public Map<String, List<DividendsCalendarData>> getDividendCalendarByPortfolioId(String portfolioId) {

                List<Holdings> holdingsList = holdingService.getAllHoldingsByPortfolioId(portfolioId);
                Map<String, List<DividendsCalendarData>> dividendByMonth = new HashMap<>();

                // Group holdings by ticker, summing quantities, so each ticker is processed once
                Map<String, BigDecimal> tickerQuantity = holdingsList.stream()
                                .collect(Collectors.groupingBy(
                                                Holdings::getTicker,
                                                Collectors.reducing(BigDecimal.ZERO, Holdings::getQuantity,
                                                                BigDecimal::add)));

                LocalDate yearStart = Year.now().atDay(1);
                LocalDate previousYearStart = yearStart.minusYears(1);

                for (Map.Entry<String, BigDecimal> entry : tickerQuantity.entrySet()) {
                        String ticker = entry.getKey();
                        BigDecimal quantity = entry.getValue();
                        MarketData marketData = marketDataService.getMarketDataByTicker(ticker);
                        if (marketData == null || marketData.getLastDividendPayment() == null
                                        || marketData.getDividends() == null) {
                                continue;
                        }
                        List<Dividend> dividendList = marketData.getDividends().stream()
                                        .filter(d -> d != null && d.getDividendDate() != null
                                                        && d.getDividendAmount() != null)
                                        .distinct()
                                        .toList();

                        List<Dividend> lastYearDividends = dividendList.stream()
                                        .filter(dividend -> dividend.getDividendDate().isBefore(yearStart)
                                                        && dividend.getDividendDate().isAfter(previousYearStart))
                                        .toList();
                        // A payer that only started distributing this year has no template
                        // year to repeat, so fall back to what it has paid so far.
                        List<Dividend> template = lastYearDividends.isEmpty()
                                        ? dividendList.stream()
                                                        .filter(dividend -> dividend.getDividendDate()
                                                                        .isAfter(yearStart))
                                                        .toList()
                                        : lastYearDividends;

                        TickerMeta meta = metaOf(marketData, dividendList, null, LocalDate.now());
                        int thisYear = yearStart.getYear();
                        int thisMonth = LocalDate.now().getMonthValue();
                        for (Dividend dividend : onePerMonth(template)) {
                                Month month = dividend.getDividendDate().getMonth();
                                // the next occurrence of that month: this year's or, once it
                                // is behind us, next year's
                                LocalDate exDate = dividend.getDividendDate().withYear(
                                                month.getValue() >= thisMonth ? thisYear : thisYear + 1);
                                dividendByMonth.computeIfAbsent(month.toString(), m -> new ArrayList<>())
                                                .add(entry(ticker, dividend.getDividendAmount(), quantity, true,
                                                                exDate, meta));
                        }
                }
                return dividendByMonth.entrySet().stream()
                                .sorted(
                                                Comparator.comparing(e -> Month.valueOf(e.getKey()).getValue()))
                                .collect(Collectors.toMap(
                                                Map.Entry::getKey,
                                                Map.Entry::getValue,
                                                (oldValue, newValue) -> oldValue,
                                                LinkedHashMap::new));
        }

        /**
         * One calendar year of payments.
         *
         * <p>
         * Settled from history wherever history exists: every dividend the ticker
         * declared for that year, valued on the shares actually held on its ex-date,
         * so a position built up or sold during the year is counted as it was and
         * not as it is now. The current year carries a scheduled leg for this month
         * and the months ahead, wherever nothing has been declared yet — those repeat
         * last year's payment pattern against today's holding, which is what the
         * un-yeared projection does for all twelve months. Scheduled entries carry
         * {@code scheduled = true}.
         *
         * <p>
         * A future year is scheduled end to end: nothing of it has happened, so
         * every month that carries no declared ex-date yet is projected from the
         * trailing twelve months' pattern on today's holding. Closed years carry no
         * scheduled leg at all.
         */
        @Override
        public Map<String, List<DividendsCalendarData>> getDividendCalendarByPortfolioId(String portfolioId,
                        Integer year) {
                if (year == null) {
                        return getDividendCalendarByPortfolioId(portfolioId);
                }

                LocalDate today = LocalDate.now();
                boolean isCurrentYear = year == today.getYear();
                boolean isFutureYear = year > today.getYear();

                Map<String, BigDecimal> tickerQuantity = holdingService.getAllHoldingsByPortfolioId(portfolioId)
                                .stream()
                                .collect(Collectors.groupingBy(
                                                Holdings::getTicker,
                                                Collectors.reducing(BigDecimal.ZERO, Holdings::getQuantity,
                                                                BigDecimal::add)));

                // Ex-date share counts need the whole transaction history, not the
                // quantity held today.
                Map<String, List<Transactions>> transactionsByTicker = transactionService
                                .findAllByPortfolioId(portfolioId).stream()
                                .filter(t -> t.getTicker() != null && t.getDate() != null)
                                .collect(Collectors.groupingBy(Transactions::getTicker));
                transactionsByTicker.values()
                                .forEach(list -> list.sort(Comparator.comparing(Transactions::getDate)));

                // A position closed since then still paid while it was held, so walk every
                // ticker that ever traded in this portfolio, not only today's holdings.
                Set<String> tickers = new LinkedHashSet<>(tickerQuantity.keySet());
                tickers.addAll(transactionsByTicker.keySet());

                Map<Month, List<DividendsCalendarData>> byMonth = new EnumMap<>(Month.class);

                for (String ticker : tickers) {
                        MarketData marketData = marketDataService.getMarketDataByTicker(ticker);
                        if (marketData == null || marketData.getDividends() == null) {
                                continue;
                        }
                        List<Dividend> dividends = marketData.getDividends().stream()
                                        .filter(d -> d != null && d.getDividendDate() != null
                                                        && d.getDividendAmount() != null)
                                        .distinct()
                                        .toList();
                        if (dividends.isEmpty()) {
                                continue;
                        }

                        List<Transactions> transactions = transactionsByTicker.getOrDefault(ticker, List.of());
                        List<Splits> splits = marketData.getSplits() == null
                                        ? List.of()
                                        : marketData.getSplits().stream()
                                                        .filter(s -> s != null && s.getSplitDate() != null)
                                                        .sorted(Comparator.comparing(Splits::getSplitDate))
                                                        .toList();

                        TickerMeta meta = metaOf(marketData, dividends,
                                        isCurrentYear || isFutureYear ? null : year, today);

                        // declared leg - every dividend this ticker announced for `year`.
                        // A future ex-date already on the provider's books counts too; the
                        // share walk simply returns today's position for it.
                        Set<Month> booked = EnumSet.noneOf(Month.class);
                        for (Dividend dividend : dividends) {
                                LocalDate date = dividend.getDividendDate();
                                if (date.getYear() != year) {
                                        continue;
                                }
                                BigDecimal shares = dividendUtils.getSharesHeldOnDate(date, transactions, splits);
                                if (shares.compareTo(BigDecimal.ZERO) <= 0) {
                                        continue;
                                }
                                byMonth.computeIfAbsent(date.getMonth(), m -> new ArrayList<>())
                                                .add(entry(ticker, dividend.getDividendAmount(), shares, false,
                                                                date, meta));
                                booked.add(date.getMonth());
                        }

                        // scheduled leg - fills the months nothing was declared for yet.
                        // The current year fills this month and the ones ahead: Yahoo lists
                        // a dividend only once its ex-date has passed, so a payment due
                        // later this month is not declared yet and would otherwise vanish
                        // from the month it lands in. A future year has nothing behind it,
                        // so all twelve are open. A closed year is history and gets no
                        // projection.
                        if (!isCurrentYear && !isFutureYear) {
                                continue;
                        }
                        BigDecimal quantity = tickerQuantity.getOrDefault(ticker, BigDecimal.ZERO);
                        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                                continue;
                        }
                        Collection<Dividend> template = isFutureYear
                                        ? onePerMonth(trailingYearTemplate(dividends, today))
                                        : scheduleTemplate(dividends, today);
                        for (Dividend dividend : template) {
                                Month month = dividend.getDividendDate().getMonth();
                                if (booked.contains(month)
                                                || (isCurrentYear && (month.getValue() < today.getMonthValue()
                                                                || paidNear(dividends,
                                                                                dividend.getDividendDate().withYear(year))))) {
                                        continue;
                                }
                                byMonth.computeIfAbsent(month, m -> new ArrayList<>())
                                                .add(entry(ticker, dividend.getDividendAmount(), quantity, true,
                                                                dividend.getDividendDate().withYear(year), meta));
                                booked.add(month);
                        }
                }

                Map<String, List<DividendsCalendarData>> result = new LinkedHashMap<>();
                byMonth.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .forEach(e -> result.put(e.getKey().name(), e.getValue()));
                return result;
        }

        /**
         * At most one payment per calendar month, the most recent one winning.
         *
         * <p>
         * A calendar year is the wrong ruler for a schedule whose ex-date drifts.
         * Realty Income moved from the 1st of the month to the last business day of
         * the month before during 2025, so both 2025-10-01 and 2025-10-31 fall in
         * October and the template year holds 13 payments for a twelve-a-year payer.
         * Projected straight forward that shows O twice in October and overstates
         * the annual figure by one month's income. The projection answers "what will
         * this month pay", so a month gets one payment and it is the latest, which
         * carries the rate in force now.
         *
         * <p>
         * This is a forward projection only. The per-year view keeps every declared
         * payment, because a year that really paid thirteen times really did.
         */
        private static Collection<Dividend> onePerMonth(List<Dividend> dividends) {
                Map<Month, Dividend> latest = new EnumMap<>(Month.class);
                for (Dividend dividend : dividends) {
                        latest.merge(dividend.getDividendDate().getMonth(), dividend,
                                        (kept, candidate) -> candidate.getDividendDate()
                                                        .isAfter(kept.getDividendDate()) ? candidate : kept);
                }
                return latest.values();
        }

        /** How far an ex-date may drift year to year and still be the same payment. */
        private static final int DRIFT_DAYS = 14;

        /** Longest ex-date → pay-date lag believed; past it the statistics pair is taken as mismatched. */
        private static final int MAX_PAY_LAG_DAYS = 120;

        /**
         * What every entry of one ticker shares: its market document, the months it
         * pays in, and the ex → pay lag read off Yahoo's statistics, with the one
         * payment that lag was measured on.
         */
        private record TickerMeta(MarketData marketData, int paymentsPerYear, List<Integer> paymentMonths,
                        Integer payLagDays, LocalDate reportedExDate, LocalDate reportedPayDate) {
        }

        /**
         * How often the ticker pays is its usual cadence — the most common yearly
         * payment count of the last five finished years, ending at {@code closedYear},
         * or at last year when null ({@link AiTickerAssembler#usualCadence}, the
         * Historical page's rule). Counting the months of one window instead let a
         * single payment Yahoo never listed flip the label: ISNPY's history has no
         * November 2025 interim, so its trailing year held May alone and a twice-a-year
         * payer read "Annual".
         *
         * <p>
         * The months start from that one window ({@code closedYear} itself, or the
         * trailing twelve months up to {@code today}), the schedule in force, and are
         * filled up to the cadence from earlier years, newest first, so a missing
         * payment does not drop its month and a moved schedule does not keep its old
         * months.
         *
         * <p>
         * The lag comes from {@code statistics.exDividendDate} and {@code
         * nextDividendDate} (Yahoo's {@code dividendDate}, the pay date). Yahoo
         * refreshes the two independently, so the pair can describe two different
         * payments: a stale ex-date against the next pay date reads one payment
         * interval too long, a fresh ex-date against the last pay date negative. A
         * real lag is shorter than the interval between payments, so anything
         * outside that is dropped and the ticker reports no pay dates at all rather
         * than wrong ones.
         */
        private static TickerMeta metaOf(MarketData marketData, List<Dividend> dividends, Integer closedYear,
                        LocalDate today) {
                LocalDate windowStart = today.minusYears(1);
                TreeSet<Integer> months = dividends.stream()
                                .map(Dividend::getDividendDate)
                                .filter(d -> closedYear != null
                                                ? d.getYear() == closedYear
                                                : d.isAfter(windowStart) && !d.isAfter(today))
                                .map(LocalDate::getMonthValue)
                                .collect(Collectors.toCollection(TreeSet::new));

                int lastFinished = closedYear != null ? closedYear : today.getYear() - 1;
                TreeMap<Integer, Integer> counts = new TreeMap<>();
                dividends.forEach(d -> counts.merge(d.getDividendDate().getYear(), 1, Integer::sum));
                // a payer with no finished year yet has only its window to go by
                int cadence = counts.headMap(lastFinished, true).isEmpty()
                                ? months.size()
                                : AiTickerAssembler.usualCadence(counts, lastFinished + 1);

                int oldest = lastFinished - AiTickerAssembler.CADENCE_WINDOW_YEARS;
                int firstFill = closedYear != null ? closedYear - 1 : lastFinished;
                for (int year = firstFill; months.size() < cadence && year > oldest; year--) {
                        int y = year;
                        dividends.stream()
                                        .map(Dividend::getDividendDate)
                                        .filter(d -> d.getYear() == y)
                                        .map(LocalDate::getMonthValue)
                                        .forEach(m -> {
                                                if (months.size() < cadence) months.add(m);
                                        });
                }
                List<Integer> paymentMonths = new ArrayList<>(months);

                MarketStatistics statistics = marketData.getStatistics();
                LocalDate ex = statistics == null ? null : statistics.getExDividendDate();
                LocalDate pay = statistics == null ? null : statistics.getNextDividendDate();
                if (ex == null || pay == null) {
                        return new TickerMeta(marketData, cadence, paymentMonths, null, null, null);
                }
                long lag = ChronoUnit.DAYS.between(ex, pay);
                long interval = 365 / Math.max(cadence, 1);
                if (lag < 0 || lag >= interval || lag > MAX_PAY_LAG_DAYS) {
                        return new TickerMeta(marketData, cadence, paymentMonths, null, null, null);
                }
                return new TickerMeta(marketData, cadence, paymentMonths, (int) lag, ex, pay);
        }

        /**
         * One calendar row. A scheduled payment within {@code DRIFT_DAYS} of the
         * ex-date Yahoo already reports is that payment, declared but not yet in the
         * dividend history (Yahoo lists one only after its ex-date), so it takes the
         * reported dates instead of last year's moved forward.
         */
        private static DividendsCalendarData entry(String ticker, BigDecimal amount, BigDecimal shares,
                        boolean scheduled, LocalDate exDate, TickerMeta meta) {
                LocalDate reportedEx = meta.reportedExDate();
                if (scheduled && reportedEx != null
                                && Math.abs(reportedEx.toEpochDay() - exDate.toEpochDay()) <= DRIFT_DAYS) {
                        exDate = reportedEx;
                }
                DividendsCalendarData data = new DividendsCalendarData(ticker, amount, shares, scheduled);
                data.setExDate(exDate);
                MarketData marketData = meta.marketData();
                data.setName(marketData.getName());
                data.setCountry(marketData.getCountry());
                data.setCurrency(marketData.getCurrency());
                data.setPaymentMonths(meta.paymentMonths());
                data.setPaymentsPerYear(meta.paymentsPerYear() > 0 ? meta.paymentsPerYear() : null);
                if (meta.payLagDays() != null) {
                        if (exDate.equals(reportedEx)) {
                                data.setPayDate(meta.reportedPayDate());
                        } else {
                                data.setPayDate(nextBusinessDay(exDate.plusDays(meta.payLagDays())));
                                data.setPayDateEstimated(true);
                        }
                }
                return data;
        }

        /** A pay date that lands on a weekend is paid the following Monday. */
        private static LocalDate nextBusinessDay(LocalDate date) {
                return switch (date.getDayOfWeek()) {
                        case SATURDAY -> date.plusDays(2);
                        case SUNDAY -> date.plusDays(1);
                        default -> date;
                };
        }

        /**
         * Whether a payment the template expects on {@code expected} has already
         * been declared, possibly in the neighbouring month.
         *
         * <p>
         * {@code booked} only knows calendar months. An ex-date that drifted from
         * the 1st of October last year to the 30th of September this year is
         * declared under September, so October would still look open and the
         * same payment would be scheduled a second time. Half a month is the most
         * the window can be: a monthly payer's next payment is ~30 days off.
         */
        private static boolean paidNear(List<Dividend> dividends, LocalDate expected) {
                return dividends.stream().anyMatch(d -> Math.abs(
                                d.getDividendDate().toEpochDay() - expected.toEpochDay()) <= DRIFT_DAYS);
        }

        /**
         * The pattern a future year repeats: the last thirteen months of payments,
         * not last calendar year's. Projecting 2028 off 2026 would freeze a rate two
         * years stale, while the trailing window always carries the one in force now.
         *
         * <p>
         * Thirteen rather than twelve so an annual payer whose ex-date drifts a few
         * weeks later each year does not fall out of its own window; {@code
         * onePerMonth} then collapses the two Novembers that buys onto the newer one.
         *
         * <p>
         * Empty when the ticker has paid nothing in that window, which is the point:
         * a former payer that stopped projects no income rather than resurrecting a
         * dead schedule.
         */
        private static List<Dividend> trailingYearTemplate(List<Dividend> dividends, LocalDate today) {
                LocalDate windowStart = today.minusMonths(13);
                return dividends.stream()
                                .filter(d -> d.getDividendDate().isAfter(windowStart))
                                .toList();
        }

        /**
         * The payment pattern a ticker is expected to repeat: last calendar year's
         * dividends, or this year's so far when it has none (a payer that only started
         * distributing this year). Same rule the un-yeared projection uses.
         */
        private List<Dividend> scheduleTemplate(List<Dividend> dividends, LocalDate today) {
                LocalDate yearStart = today.withDayOfYear(1);
                LocalDate previousYearStart = yearStart.minusYears(1);
                List<Dividend> lastYear = dividends.stream()
                                .filter(d -> !d.getDividendDate().isBefore(previousYearStart)
                                                && d.getDividendDate().isBefore(yearStart))
                                .toList();
                if (!lastYear.isEmpty()) {
                        return lastYear;
                }
                return dividends.stream()
                                .filter(d -> !d.getDividendDate().isBefore(yearStart))
                                .toList();
        }
}
