package com.dev.alex.Controller;

import com.dev.alex.Model.Portfolios;
import com.dev.alex.Repository.PortfolioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

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
    @Deprecated
    @GetMapping("/byUserId")
    public List<Portfolios> findAllPortfoliosByUserId(@RequestBody String userId){
        return portfolioRepository.findAllByUserId(userId);
    }

    @GetMapping("/portfolios")
    public List<Portfolios> findAllPortfoliosByUserId(){
        return portfolioRepository.findAll();
    }
}
