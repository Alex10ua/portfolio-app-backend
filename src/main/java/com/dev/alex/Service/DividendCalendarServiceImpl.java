package com.dev.alex.Service;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.NonDbModel.Dividend;
import com.dev.alex.Model.NonDbModel.DividendsCalendarData;
import com.dev.alex.Service.Interface.DividendCalendarService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DividendCalendarServiceImpl implements DividendCalendarService {
    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private MarketDataServiceImpl marketDataService;


    @Override
    public Map<String, List<DividendsCalendarData>> getDividendCalendarByPortfolioId(String portfolioId) {

        List<Holdings> holdingsList = holdingService.getAllHoldingsByPortfolioId(portfolioId);
        Map<String, List<DividendsCalendarData>> dividendByMonth = new HashMap<>();

        for (Holdings holdings : holdingsList) {
            MarketData marketData = marketDataService.getMarketDataByTicker(holdings.getTicker());
            DividendsCalendarData dividendsCalendarData = new DividendsCalendarData();
            if (marketData.getLastDividendPayment() != null){
                List<Dividend> dividendList = marketData.getDividends();
                LocalDate dateYear = LocalDateTime
                        .ofInstant(
                                Year.now().atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant(),
                                ZoneId.systemDefault()
                        )
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
                    for (Dividend dividend:lastYearDividends){
                        String monthName = dividend.getDividendDate().getMonth().toString();
                        String yearMonth = dividend.getDividendDate()
                                .format(DateTimeFormatter.ofPattern("yyyy-MM"));
                        dividendsCalendarData.setTicker(holdings.getTicker());
                        dividendsCalendarData.setDividendAmount(dividend.getDividendAmount());
                        dividendsCalendarData.setStockQuantity(holdings.getQuantity());
                        dividendByMonth.merge(
                                monthName,
                                new ArrayList<>(List.of(dividendsCalendarData)),
                                (existing, newList) -> {
                                    existing.addAll(newList);
                                    return existing;
                                }
                        );

                    }
                }else {
                    List<Dividend> yearToDateDividends = dividendList.stream()
                            .filter(dividend -> dividend.getDividendDate().isAfter(dateYear))
                            .toList();
                    for (Dividend dividend:yearToDateDividends){
                        String yearMonth = dividend.getDividendDate()
                                .format(DateTimeFormatter.ofPattern("yyyy-MM"));
                        String monthName = dividend.getDividendDate().getMonth().toString();
                        dividendsCalendarData.setTicker(holdings.getTicker());
                        dividendsCalendarData.setDividendAmount(dividend.getDividendAmount());
                        dividendsCalendarData.setStockQuantity(holdings.getQuantity());
                        dividendByMonth.merge(
                                monthName,
                                new ArrayList<>(List.of(dividendsCalendarData)),
                                (existing, newList) -> {
                                    existing.addAll(newList);
                                    return existing;
                                }
                        );
                    }
                }
            }
        }
        return dividendByMonth.entrySet().stream()
                .sorted(
                        Comparator.comparing(e -> Month.valueOf(e.getKey()).getValue())
                )
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new
                ));
    }
}
