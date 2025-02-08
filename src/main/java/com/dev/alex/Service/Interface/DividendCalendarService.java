package com.dev.alex.Service.Interface;

import com.dev.alex.Model.NonDbModel.DividendsCalendarData;

import java.util.List;
import java.util.Map;

public interface DividendCalendarService {

    Map<String, List<DividendsCalendarData>> getDividendCalendarByPortfolioId(String portfolioId);
}
