package com.dev.alex.Service.Dividends;

import com.dev.alex.Model.Dividend;
import com.dev.alex.Model.Enums.DividendFrequency;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class DividendFrequencyAnalyzer {

    public DividendFrequency determineDividendFrequency(List<Dividend> dividends) {
        if (dividends.size() < 2) {
            throw new IllegalArgumentException("Not enough dividend data to determine frequency.");
        }

        // Step 1: Sort dividends by date
        dividends.sort(Comparator.comparing(Dividend::getDividendDate));

        // Step 2: Calculate intervals
        List<Long> intervals = calculateIntervals(dividends);

        // Step 3: Categorize intervals
        Map<DividendFrequency, Integer> frequencyCounts = new EnumMap<>(DividendFrequency.class);
        for (DividendFrequency freq : DividendFrequency.values()) {
            frequencyCounts.put(freq, 0);
        }

        for (long days : intervals) {
            DividendFrequency freq = categorizeInterval(days);
            frequencyCounts.put(freq, frequencyCounts.get(freq) + 1);
        }

        // Step 4: Determine the most common frequency
        DividendFrequency mostCommonFrequency = DividendFrequency.OTHER;
        int maxCount = 0;
        for (Map.Entry<DividendFrequency, Integer> entry : frequencyCounts.entrySet()) {
            if (entry.getValue() > maxCount && entry.getKey() != DividendFrequency.OTHER) {
                maxCount = entry.getValue();
                mostCommonFrequency = entry.getKey();
            }
        }

        return mostCommonFrequency;
    }

    private List<Long> calculateIntervals(List<Dividend> dividends) {
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < dividends.size(); i++) {
            Date previousDate = dividends.get(i - 1).getDividendDate();
            Date currentDate = dividends.get(i).getDividendDate();

            long diffInMillis = currentDate.getTime() - previousDate.getTime();
            long diffInDays = TimeUnit.DAYS.convert(diffInMillis, TimeUnit.MILLISECONDS);

            intervals.add(diffInDays);
        }
        return intervals;
    }

    private DividendFrequency categorizeInterval(long days) {
        final double tolerance = 0.15; // 15% tolerance

        // Expected intervals in days
        final int quarterlyInterval = 91;
        final int semiAnnualInterval = 182;
        final int annualInterval = 365;

        if (isWithinTolerance(days, quarterlyInterval, tolerance)) {
            return DividendFrequency.QUARTERLY;
        } else if (isWithinTolerance(days, semiAnnualInterval, tolerance)) {
            return DividendFrequency.SEMI_ANNUALLY;
        } else if (isWithinTolerance(days, annualInterval, tolerance)) {
            return DividendFrequency.ANNUALLY;
        } else {
            return DividendFrequency.OTHER;
        }
    }

    private boolean isWithinTolerance(long actualDays, int expectedDays, double tolerance) {
        double lowerBound = expectedDays * (1 - tolerance);
        double upperBound = expectedDays * (1 + tolerance);
        return actualDays >= lowerBound && actualDays <= upperBound;
    }
}
