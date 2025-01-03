package com.dev.alex.Controller;


import com.dev.alex.Model.NonDbModel.DividendInfoCompleteData;
import com.dev.alex.Service.DividendsServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "http://localhost:3000")//fix Access-Control-Allow-Origin
@RestController
@RequestMapping("/api/v1")
public class DividendsController {
    @Autowired
    private DividendsServiceImpl dividendsService;

    @GetMapping("/{portfolioId}/dividends")
    public DividendInfoCompleteData getDividendsInfoByPortfolioId(@PathVariable String portfolioId){
        return dividendsService.getAllDividendsInfoByPortfolioId(portfolioId);
    }

}
