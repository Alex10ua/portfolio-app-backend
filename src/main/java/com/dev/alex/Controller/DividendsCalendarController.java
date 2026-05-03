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

    @GetMapping("/{portfolioId}/dividends-calendar")
    public Map<String, List<DividendsCalendarData>> getDividendCalendarByPortfolioId(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return dividendCalendarService.getDividendCalendarByPortfolioId(portfolioId);
    }
}
