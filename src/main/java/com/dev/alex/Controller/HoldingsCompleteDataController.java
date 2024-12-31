package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.HoldingsCompleteData;
import com.dev.alex.Service.HoldingsCompleteDataServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin(origins = "http://localhost:3000")//fix Access-Control-Allow-Origin
@RequestMapping("/api/v1")
public class HoldingsCompleteDataController {
    @Autowired
    private HoldingsCompleteDataServiceImpl holdingsCompleteDataService;

    @GetMapping("/{portfolioId}")
    public List<HoldingsCompleteData> getAllHoldingsByPortfolioId(@PathVariable String portfolioId) {
        return holdingsCompleteDataService.getAllHoldingsByPortfolioId(portfolioId);
    }
}
