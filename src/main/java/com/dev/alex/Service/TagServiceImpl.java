package com.dev.alex.Service;

import com.dev.alex.Model.TickerTags;
import com.dev.alex.Repository.TickerTagsRepository;
import com.dev.alex.Service.Interface.TagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TagServiceImpl implements TagService {

    @Autowired
    private TickerTagsRepository tickerTagsRepository;

    @Override
    public List<TickerTags> getAllTagsForUser(String username, String portfolioId) {
        return tickerTagsRepository.findAllByUsernameAndPortfolioId(username, portfolioId);
    }

    @Override
    public TickerTags getTagsForTicker(String username, String portfolioId, String ticker) {
        return tickerTagsRepository
                .findByUsernameAndPortfolioIdAndTicker(username, portfolioId, ticker.toUpperCase())
                .orElse(new TickerTags(null, username, portfolioId, ticker.toUpperCase(), new ArrayList<>(), LocalDate.now()));
    }

    @Override
    public TickerTags setTagsForTicker(String username, String portfolioId, String ticker, List<String> tags) {
        String normalizedTicker = ticker.toUpperCase();

        List<String> normalized = tags.stream()
                .map(t -> t.toLowerCase().trim().replaceAll("[^a-z0-9-]", ""))
                .filter(t -> !t.isEmpty() && t.length() <= 32)
                .distinct()
                .collect(Collectors.toList());

        Optional<TickerTags> existing = tickerTagsRepository.findByUsernameAndPortfolioIdAndTicker(username, portfolioId, normalizedTicker);

        if (normalized.isEmpty()) {
            existing.ifPresent(tickerTagsRepository::delete);
            return new TickerTags(null, username, portfolioId, normalizedTicker, new ArrayList<>(), LocalDate.now());
        }

        TickerTags doc = existing.orElseGet(() ->
                new TickerTags(UUID.randomUUID().toString(), username, portfolioId, normalizedTicker, normalized, LocalDate.now()));

        doc.setTags(normalized);
        doc.setUpdatedAt(LocalDate.now());

        return tickerTagsRepository.save(doc);
    }

    @Override
    public List<String> getAllDistinctTagNames(String username, String portfolioId) {
        return tickerTagsRepository.findAllByUsernameAndPortfolioId(username, portfolioId).stream()
                .flatMap(tt -> tt.getTags().stream())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }
}
