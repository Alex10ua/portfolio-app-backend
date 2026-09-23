package com.dev.alex.Controller;

import com.dev.alex.Model.TickerTags;
import com.dev.alex.Service.Interface.TagService;
import com.dev.alex.Service.PortfolioAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Tags are keyed by (username, portfolioId, ticker). Rows are filtered by the session user, so
 * nothing leaked without the ownership check — but a user could still write tag rows under a
 * portfolioId that is not theirs. Every endpoint now asserts ownership like the other
 * portfolio-scoped controllers.
 */
@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    @Autowired
    private TagService tagService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @GetMapping
    public ResponseEntity<List<TickerTags>> getAllTags(@RequestParam String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return ResponseEntity.ok(tagService.getAllTagsForUser(authentication.getName(), portfolioId));
    }

    @GetMapping("/names")
    public ResponseEntity<List<String>> getTagNames(@RequestParam String portfolioId, Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return ResponseEntity.ok(tagService.getAllDistinctTagNames(authentication.getName(), portfolioId));
    }

    @GetMapping("/{ticker}")
    public ResponseEntity<TickerTags> getTagsForTicker(@PathVariable String ticker,
                                                        @RequestParam String portfolioId,
                                                        Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return ResponseEntity.ok(tagService.getTagsForTicker(authentication.getName(), portfolioId, ticker));
    }

    @PutMapping("/{ticker}")
    public ResponseEntity<TickerTags> setTagsForTicker(@PathVariable String ticker,
                                                        @RequestParam String portfolioId,
                                                        @RequestBody Map<String, List<String>> body,
                                                        Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        List<String> tags = body.getOrDefault("tags", List.of());
        return ResponseEntity.ok(tagService.setTagsForTicker(authentication.getName(), portfolioId, ticker, tags));
    }
}
