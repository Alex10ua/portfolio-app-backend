package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.DiversificationCompleteData;
import com.dev.alex.Service.DiversificationServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.query.Param;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "http://localhost:3000")//fix Access-Control-Allow-Origin
@RestController
@RequestMapping("/api/v1")
public class DiversificationController {
    @Autowired
    private DiversificationServiceImpl diversificationService;

    @GetMapping("/{portfolioId}/diversification")
    public DiversificationCompleteData getAllDiversificationInfo(@PathVariable String portfolioId){
        return diversificationService.getAllDiversificationInfo(portfolioId);
    }
}
