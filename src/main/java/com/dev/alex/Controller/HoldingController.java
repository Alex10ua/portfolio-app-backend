package com.dev.alex.Controller;

import com.dev.alex.Model.Holdings;
import com.dev.alex.Repository.HoldingsRepository;
import com.dev.alex.Service.HoldingServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(origins = "http://localhost:3000")//fix Access-Control-Allow-Origin
@RestController
@RequestMapping("/api/v1")
public class HoldingController {
    @Autowired
    private HoldingServiceImpl holdingService;

    @GetMapping("/{portfolioId}")
    public List<Holdings> getAllHoldingsByPortfolioId(@PathVariable String portfolioId){
        return holdingService.getAllHoldingsByPortfolioId(portfolioId);
    }
}
