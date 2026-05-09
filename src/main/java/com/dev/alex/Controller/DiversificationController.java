package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.DiversificationCompleteData;
import com.dev.alex.Service.DiversificationServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DiversificationController {
    @Autowired
    private DiversificationServiceImpl diversificationService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @GetMapping("/{portfolioId}/diversification")
    public DiversificationCompleteData getAllDiversificationInfo(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return diversificationService.getAllDiversificationInfo(portfolioId);
    }
}
