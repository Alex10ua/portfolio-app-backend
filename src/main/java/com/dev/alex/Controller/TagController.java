package com.dev.alex.Controller;

import com.dev.alex.Model.TickerTags;
import com.dev.alex.Service.Interface.TagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    @Autowired
    private TagService tagService;

    @GetMapping
    public ResponseEntity<List<TickerTags>> getAllTags(Authentication authentication) {
        return ResponseEntity.ok(tagService.getAllTagsForUser(authentication.getName()));
    }

    @GetMapping("/names")
    public ResponseEntity<List<String>> getTagNames(Authentication authentication) {
        return ResponseEntity.ok(tagService.getAllDistinctTagNames(authentication.getName()));
    }

    @GetMapping("/{ticker}")
    public ResponseEntity<TickerTags> getTagsForTicker(@PathVariable String ticker, Authentication authentication) {
        return ResponseEntity.ok(tagService.getTagsForTicker(authentication.getName(), ticker));
    }

    @PutMapping("/{ticker}")
    public ResponseEntity<TickerTags> setTagsForTicker(@PathVariable String ticker,
                                                        @RequestBody Map<String, List<String>> body,
                                                        Authentication authentication) {
        List<String> tags = body.getOrDefault("tags", List.of());
        return ResponseEntity.ok(tagService.setTagsForTicker(authentication.getName(), ticker, tags));
    }
}
