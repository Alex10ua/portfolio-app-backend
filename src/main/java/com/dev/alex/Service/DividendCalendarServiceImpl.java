package com.dev.alex.Service;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.DividendsCalendarData;
import com.dev.alex.Model.NonDbModel.Splits;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Service.Dividends.DividendUtils;
import com.dev.alex.Service.Interface.DividendCalendarService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
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

                for (Map.Entry<String, BigDecimal> entry : tickerQuantity.entrySet()) {
                        String ticker = entry.getKey();
                        BigDecimal quantity = entry.getValue();
                        MarketData marketData = marketDataService.getMarketDataByTicker(ticker);
                        if (marketData.getLastDividendPayment() != null && marketData.getDividends() != null) {
                                List<Dividend> dividendList = marketData.getDividends().stream()
                                                .distinct()
                                                .toList();
                                LocalDate dateYear = LocalDateTime
                                                .ofInstant(
                                                                Year.now().atDay(1).atStartOfDay(ZoneId.systemDefault())
                                                                                .toInstant(),
                                                                ZoneId.systemDefault())
                                                .toLocalDate();

                                ZonedDateTime zdt = Year.now()
                                                .minusYears(1)
                                                .atDay(1)
                                                .atStartOfDay(ZoneId.systemDefault());

                                LocalDate oneYearAgoDate = zdt.toLocalDate();
                                List<Dividend> lastYearDividends = dividendList.stream()
                                                .filter(dividend -> dividend.getDividendDate().isBefore(dateYear)
                                                                && dividend.getDividendDate().isAfter(oneYearAgoDate))
                                                .toList();
                                if (!lastYearDividends.isEmpty()) {
                                        for (Dividend dividend : lastYearDividends) {
                                                String monthName = dividend.getDividendDate().getMonth().toString();
                                                DividendsCalendarData calendarEntry = new DividendsCalendarData(
                                                                ticker, dividend.getDividendAmount(), quantity);
                                                dividendByMonth.merge(
                                                                monthName,
                                                                new ArrayList<>(List.of(calendarEntry)),
                                                                (existing, newList) -> {
                                                                        existing.addAll(newList);
                                                                        return existing;
                                                                });
                                        }
                                } else {
                                        List<Dividend> yearToDateDividends = dividendList.stream()
                                                        .filter(dividend -> dividend.getDividendDate()
                                                                        .isAfter(dateYear))
                                                        .toList();
                                        for (Dividend dividend : yearToDateDividends) {
                                                String monthName = dividend.getDividendDate().getMonth().toString();
                                                DividendsCalendarData calendarEntry = new DividendsCalendarData(
                                                                ticker, dividend.getDividendAmount(), quantity);
                                                dividendByMonth.merge(
                                                                monthName,
                                                                new ArrayList<>(List.of(calendarEntry)),
                                                                (existing, newList) -> {
                                                                        existing.addAll(newList);
                                                                        return existing;
                                                                });
                                        }
                                }
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
         * not as it is now. Only the current year carries a scheduled leg, and only
         * for months ahead that nothing has been declared for — those repeat last
         * year's payment pattern against today's holding, which is what the
         * un-yeared projection does for all twelve months.
         */
        @Override
        public Map<String, List<DividendsCalendarData>> getDividendCalendarByPortfolioId(String portfolioId,
                        Integer year) {
                if (year == null) {
                        return getDividendCalendarByPortfolioId(portfolioId);
                }

                LocalDate today = LocalDate.now();
                boolean isCurrentYear = year == today.getYear();

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
                                                .add(new DividendsCalendarData(ticker, dividend.getDividendAmount(),
                                                                shares));
                                booked.add(date.getMonth());
                        }

                        // scheduled leg - fills the months this year that nothing was declared
                        // for yet, on last year's pattern
                        if (!isCurrentYear) {
                                continue;
                        }
                        BigDecimal quantity = tickerQuantity.getOrDefault(ticker, BigDecimal.ZERO);
                        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                                continue;
                        }
                        for (Dividend dividend : scheduleTemplate(dividends, today)) {
                                Month month = dividend.getDividendDate().getMonth();
                                if (month.getValue() <= today.getMonthValue() || booked.contains(month)) {
                                        continue;
                                }
                                byMonth.computeIfAbsent(month, m -> new ArrayList<>())
                                                .add(new DividendsCalendarData(ticker, dividend.getDividendAmount(),
                                                                quantity));
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
