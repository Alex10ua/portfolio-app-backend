package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.WealthData;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.WealthServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/{portfolioId}/wealth")
public class WealthController {
    @Autowired private PortfolioAccessService portfolioAccessService;
    @Autowired private WealthServiceImpl wealth;

    @GetMapping
    public WealthData.Summary current(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return wealth.current(portfolioId);
    }

    @GetMapping("/history")
    public List<WealthData.HistoryPoint> history(@PathVariable String portfolioId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return wealth.history(portfolioId, from, to);
    }
}
