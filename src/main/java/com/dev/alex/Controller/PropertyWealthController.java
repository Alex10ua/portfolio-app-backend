package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.WealthData;
import com.dev.alex.Service.PortfolioAccessService;
import com.dev.alex.Service.WealthServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/v1/{portfolioId}/properties/{propertyId}/history")
public class PropertyWealthController {
    @Autowired private PortfolioAccessService portfolioAccessService;
    @Autowired private WealthServiceImpl wealth;

    @GetMapping
    public List<WealthData.PropertyHistoryPoint> history(@PathVariable String portfolioId,
            @PathVariable String propertyId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return wealth.propertyHistory(portfolioId, propertyId);
    }
}
