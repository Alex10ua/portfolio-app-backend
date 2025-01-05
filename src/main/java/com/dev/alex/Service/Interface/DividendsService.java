package com.dev.alex.Service.Interface;

import com.dev.alex.Model.NonDbModel.DividendInfoCompleteData;

import java.util.List;

public interface DividendsService {

    DividendInfoCompleteData getAllDividendsInfoByPortfolioId(String portfolioId);
}
