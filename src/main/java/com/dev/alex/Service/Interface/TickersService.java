package com.dev.alex.Service.Interface;

import com.dev.alex.Model.Tickers;

public interface TickersService {

    void createTicker(String ticker);
    Tickers getTicker(String ticker);
    void saveIfNotExists(String ticker);
}
