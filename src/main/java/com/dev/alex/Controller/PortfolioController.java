package com.dev.alex.Controller;

import com.dev.alex.Model.Portfolios;
import com.dev.alex.Repository.PortfolioRepository;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.PortfoliosServiceImpl;
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
    @Autowired
    private PortfoliosServiceImpl portfoliosService;

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

    @PutMapping("/{portfolioId}/updatePortfolio")
    public Portfolios updatePortfolio(@PathVariable String portfolioId,
                                      @RequestBody Map<String, String> body,
                                      Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        String newName = body.get("portfolioName");
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("Portfolio name is required");
        }
        // portfolioId stays immutable — it embeds the original name but is opaque
        // everywhere and is the FK for all child collections
        Portfolios portfolio = portfolioRepository.findByPortfolioId(portfolioId);
        portfolio.setPortfolioName(newName.trim());
        if (body.containsKey("description")) {
            portfolio.setDescription(body.get("description"));
        }
        portfolio.setUpdatedAt(new java.util.Date());
        return portfolioRepository.save(portfolio);
    }

    @DeleteMapping("/{portfolioId}/deletePortfolio")
    public Map<String, Boolean> deletePortfolio(@PathVariable String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        portfoliosService.deletePortfolioCascade(portfolioId);
        return Map.of("deleted", Boolean.TRUE);
    }
}
