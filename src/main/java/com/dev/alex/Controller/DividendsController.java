package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.DividendInfoCompleteData;
import com.dev.alex.Service.DividendsServiceImpl;
import com.dev.alex.Service.PortfolioAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DividendsController {
    @Autowired
    private DividendsServiceImpl dividendsService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @GetMapping("/{portfolioId}/dividends")
    public DividendInfoCompleteData getDividendsInfoByPortfolioId(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return dividendsService.getAllReceivedDividendsInfoFromTransactions(portfolioId);
    }
}
