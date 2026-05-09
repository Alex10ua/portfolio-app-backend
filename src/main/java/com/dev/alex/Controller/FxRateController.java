package com.dev.alex.Controller;

import com.dev.alex.Service.FxRateServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "http://localhost:3001")
public class FxRateController {

    @Autowired
    private FxRateServiceImpl fxRateService;

    @GetMapping("/fx-rates")
    public Map<String, BigDecimal> getAllFxRates() {
        return fxRateService.getAllRatesAsMap();
    }
}
