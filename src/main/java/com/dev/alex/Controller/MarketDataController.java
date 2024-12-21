package com.dev.alex.Controller;

import com.dev.alex.Model.MarketData;
import com.dev.alex.Model.Portfolios;
import com.dev.alex.Model.Transactions;
import com.dev.alex.Service.MarketDataServiceImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@CrossOrigin(origins = "http://localhost:3000")//fix Access-Control-Allow-Origin
@RestController
@RequestMapping("/api/v1")
public class MarketDataController {

    private MarketDataServiceImpl marketDataService;

    @PutMapping("/{ticker}/update")
    public ResponseEntity<String> updatePriceByTicker(@PathVariable String ticker, BigDecimal price){
        marketDataService.updatePriceByTicker(ticker.toUpperCase(), price);
        return ResponseEntity.ok("updated");
    }
}
