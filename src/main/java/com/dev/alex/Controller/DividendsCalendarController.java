package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.DividendsCalendarData;
import com.dev.alex.Service.DividendCalendarServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DividendsCalendarController {
    @Autowired
    private DividendCalendarServiceImpl dividendCalendarService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    /**
     * Month name -> payments. Without {@code year} this stays the rolling
     * projection older clients expect; with one it reports that calendar year
     * (closed years as paid, the current year paid-to-date plus schedule).
     */
    @GetMapping("/{portfolioId}/dividends-calendar")
    public Map<String, List<DividendsCalendarData>> getDividendCalendarByPortfolioId(
            @PathVariable String portfolioId,
            @RequestParam(required = false) Integer year,
            Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return dividendCalendarService.getDividendCalendarByPortfolioId(portfolioId, year);
    }
}
