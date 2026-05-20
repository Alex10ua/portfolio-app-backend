package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.PerformanceData;
import com.dev.alex.Model.NonDbModel.PerformancePoint;
import com.dev.alex.Service.PortfolioPerformanceServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(origins = "http://localhost:3001")
@RestController
@RequestMapping("/api/v1")
public class PortfolioPerformanceController {

    @Autowired
    private PortfolioPerformanceServiceImpl performanceService;

    @GetMapping("/{portfolioId}/performance")
    public PerformanceData getPerformance(
            @PathVariable String portfolioId,
            @RequestParam(defaultValue = "ALL") String period) {
        return performanceService.getPerformance(portfolioId, period);
    }

    @GetMapping("/{portfolioId}/portfolio-history")
    public ResponseEntity<List<PerformancePoint>> getPortfolioHistory(
            @PathVariable String portfolioId) {
        return ResponseEntity.ok(performanceService.getMonthlyHistory(portfolioId));
    }
}
