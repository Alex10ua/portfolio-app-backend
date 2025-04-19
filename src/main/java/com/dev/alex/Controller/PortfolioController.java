package com.dev.alex.Controller;

import com.dev.alex.Model.Portfolios;
import com.dev.alex.Repository.PortfolioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@CrossOrigin(origins = "http://localhost:3000")//fix Access-Control-Allow-Origin
@RestController
@RequestMapping("/api/v1")
public class PortfolioController {
    @Autowired
    private PortfolioRepository portfolioRepository;

    @PostMapping("/createPortfolio")
    public Portfolios createPortfolio(@RequestBody Portfolios portfolio){
        portfolio.setPortfolioId(UUID.randomUUID().toString().concat(portfolio.getPortfolioName()));
        return portfolioRepository.save(portfolio);
    }

    @GetMapping("/{portfolioId}/firstTradeYear")
    public Map<String, Integer> getFirstTradeYear(@PathVariable String portfolioId){
        Portfolios portfolio = portfolioRepository.findByPortfolioId(portfolioId);
        Map<String, Integer> response = new HashMap<>();
        response.put("firstTradeYear", portfolio.getFirstTradeYear().getYear());
        return response;
    }


    @GetMapping("/portfolios")
    public List<Portfolios> findAllPortfoliosByUserId(){
        return portfolioRepository.findAll();
    }
}
