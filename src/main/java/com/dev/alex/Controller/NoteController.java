package com.dev.alex.Controller;

import com.dev.alex.Model.TickerNotes;
import com.dev.alex.Service.Interface.NoteService;
import com.dev.alex.Service.PortfolioAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notes")
public class NoteController {

    @Autowired
    private NoteService noteService;
    @Autowired
    private PortfolioAccessService portfolioAccessService;

    @GetMapping("/{ticker}")
    public ResponseEntity<TickerNotes> getNoteForTicker(@PathVariable String ticker,
                                                        @RequestParam String portfolioId,
                                                        Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        return ResponseEntity.ok(noteService.getNoteForTicker(authentication.getName(), portfolioId, ticker));
    }

    @PutMapping("/{ticker}")
    public ResponseEntity<TickerNotes> setNoteForTicker(@PathVariable String ticker,
                                                        @RequestParam String portfolioId,
                                                        @RequestBody Map<String, String> body,
                                                        Authentication authentication) {
        portfolioAccessService.assertOwnership(portfolioId, authentication.getName());
        String note = body.getOrDefault("note", "");
        return ResponseEntity.ok(noteService.setNoteForTicker(authentication.getName(), portfolioId, ticker, note));
    }
}
