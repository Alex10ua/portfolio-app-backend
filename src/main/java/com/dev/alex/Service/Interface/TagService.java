package com.dev.alex.Service.Interface;

import com.dev.alex.Model.TickerTags;

import java.util.List;

public interface TagService {
    List<TickerTags> getAllTagsForUser(String username);
    TickerTags getTagsForTicker(String username, String ticker);
    TickerTags setTagsForTicker(String username, String ticker, List<String> tags);
    List<String> getAllDistinctTagNames(String username);
}
