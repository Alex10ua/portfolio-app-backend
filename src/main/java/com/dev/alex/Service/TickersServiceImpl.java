package com.dev.alex.Service;

import com.dev.alex.Model.Tickers;
import com.dev.alex.Repository.TickersRepository;
import com.dev.alex.Service.Interface.TickersService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TickersServiceImpl implements TickersService {
    @Autowired
    private TickersRepository tickersRepository;

    @Override
    public void createTicker(String ticker) {
        tickersRepository.save(new Tickers(ticker));
    }

    @Override
    public Tickers getTicker(String ticker) {
        return tickersRepository.findByTicker(ticker);
    }

    @Override
    public void saveIfNotExists(String ticker) {
        if (tickersRepository.findByTicker(ticker) == null) {
            createTicker(ticker);
        }
    }
}
