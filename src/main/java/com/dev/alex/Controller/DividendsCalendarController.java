package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.DividendsCalendarData;
import com.dev.alex.Service.DividendCalendarServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "http://localhost:3000")//fix Access-Control-Allow-Origin
@RestController
@RequestMapping("/api/v1")
public class DividendsCalendarController {
    @Autowired
    private DividendCalendarServiceImpl dividendCalendarService;

    @GetMapping("/{portfolioId}/dividends-calendar")
    public Map<String, List<DividendsCalendarData>> getDividendCalendarByPortfolioId(@PathVariable String portfolioId){
        return dividendCalendarService.getDividendCalendarByPortfolioId(portfolioId);
    }

}
