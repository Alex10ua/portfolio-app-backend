package com.dev.alex.Service;

import com.dev.alex.Repository.PortfolioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class PortfolioAccessService {

    @Autowired
    private PortfolioRepository portfolioRepository;

    public void assertOwnership(String portfolioId, String username) {
        var portfolio = portfolioRepository.findByPortfolioIdAndUsername(portfolioId, username);
        if (portfolio == null) {
            throw new AccessDeniedException("Portfolio not found or access denied");
        }
    }
}
