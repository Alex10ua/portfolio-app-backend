package com.dev.alex.Controller;

import com.dev.alex.Model.Portfolios;
import com.dev.alex.Repository.PortfolioRepository;
import com.dev.alex.Service.PortfolioAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class PortfolioController {
    @Autowired
    private PortfolioRepository portfolioRepository;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @PostMapping("/createPortfolio")
    public Portfolios createPortfolio(@RequestBody Portfolios portfolio, Authentication authentication) {
        portfolio.setPortfolioId(UUID.randomUUID().toString().concat(portfolio.getPortfolioName()));
        portfolio.setUsername(authentication.getName());
        return portfolioRepository.save(portfolio);
    }

    @GetMapping("/{portfolioId}/firstTradeYear")
    public Map<String, Integer> getFirstTradeYear(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        Portfolios portfolio = portfolioRepository.findByPortfolioId(portfolioId);
        Map<String, Integer> response = new HashMap<>();
        response.put("firstTradeYear", portfolio.getFirstTradeYear() != null ? portfolio.getFirstTradeYear().getYear() : null);
        return response;
    }

    @GetMapping("/portfolios")
    public List<Portfolios> findAllPortfoliosByUserId(Authentication authentication) {
        return portfolioRepository.findAllByUsername(authentication.getName());
    }
}
