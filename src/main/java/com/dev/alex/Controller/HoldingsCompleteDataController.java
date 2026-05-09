package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.HoldingsCompleteData;
import com.dev.alex.Service.HoldingsCompleteDataServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class HoldingsCompleteDataController {
    @Autowired
    private HoldingsCompleteDataServiceImpl holdingsCompleteDataService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @GetMapping("/{portfolioId}")
    public List<HoldingsCompleteData> getAllHoldingsByPortfolioId(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return holdingsCompleteDataService.getAllHoldingsByPortfolioId(portfolioId);
    }
}
