package com.dev.alex.Service.Interface;

import com.dev.alex.Model.NonDbModel.DividendsCalendarData;

import java.util.List;
import java.util.Map;

public interface DividendCalendarService {

    Map<String, List<DividendsCalendarData>> getDividendCalendarByPortfolioId(String portfolioId);

    /**
     * The same calendar pinned to one calendar year.
     *
     * <p>A closed year reports what was actually paid: every dividend the ticker
     * declared that year, valued on the shares held on its ex-date. The current
     * year is a hybrid — declared payments on those ex-date share counts, plus
     * the months nothing is declared for yet scheduled from last year's pattern
     * on today's holding. A {@code null} year falls back to the un-yeared rolling
     * projection.
     */
    Map<String, List<DividendsCalendarData>> getDividendCalendarByPortfolioId(String portfolioId, Integer year);
}
