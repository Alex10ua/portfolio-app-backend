package com.dev.alex.Controller;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Service.HoldingServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class HoldingController {
    @Autowired
    private HoldingServiceImpl holdingService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @GetMapping("/{portfolioId}/deprecated")
    public List<Holdings> getAllHoldingsByPortfolioId(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return holdingService.getAllHoldingsByPortfolioId(portfolioId);
    }
}
