package com.dev.alex.Service.Interface;

import com.dev.alex.Model.TickerTags;

import java.util.List;

public interface TagService {
    List<TickerTags> getAllTagsForUser(String username, String portfolioId);
    TickerTags getTagsForTicker(String username, String portfolioId, String ticker);
    TickerTags setTagsForTicker(String username, String portfolioId, String ticker, List<String> tags);
    List<String> getAllDistinctTagNames(String username, String portfolioId);
}
