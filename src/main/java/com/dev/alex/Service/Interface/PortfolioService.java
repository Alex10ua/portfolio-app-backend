package com.dev.alex.Service.Interface;

import com.dev.alex.Model.Portfolios;

public interface PortfolioService {

    //Portfolios getPortfolioByUserId(String userId);

    /** Deletes the portfolio and every document keyed by its portfolioId. */
    void deletePortfolioCascade(String portfolioId);
}
