package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.PerformanceData;
import com.dev.alex.Model.NonDbModel.PerformancePoint;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.PortfolioPerformanceServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "http://localhost:3001")
@RestController
@RequestMapping("/api/v1")
public class PortfolioPerformanceController {

    @Autowired
    private PortfolioPerformanceServiceImpl performanceService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @GetMapping("/{portfolioId}/performance")
    public PerformanceData getPerformance(
            @PathVariable String portfolioId,
            @RequestParam(defaultValue = "ALL") String period,
            Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return performanceService.getPerformance(portfolioId, period);
    }

    @GetMapping("/{portfolioId}/portfolio-history")
    public ResponseEntity<List<PerformancePoint>> getPortfolioHistory(
            @PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return ResponseEntity.ok(performanceService.getMonthlyHistory(portfolioId));
    }

    @GetMapping("/{portfolioId}/realizedPnL")
    public ResponseEntity<Map<String, BigDecimal>> getRealizedPnL(
            @PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return ResponseEntity.ok(performanceService.getRealizedPnLByCurrency(portfolioId));
    }
}
