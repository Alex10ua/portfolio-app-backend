package com.dev.alex.Service;

import com.dev.alex.Model.TickerNotes;
import com.dev.alex.Repository.TickerNotesRepository;
import com.dev.alex.Service.Interface.NoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Service
public class NotesServiceImpl implements NoteService {

    private static final int MAX_NOTE_LENGTH = 5000;

    @Autowired
    private TickerNotesRepository tickerNotesRepository;

    @Override
    public TickerNotes getNoteForTicker(String username, String portfolioId, String ticker) {
        return tickerNotesRepository
                .findByUsernameAndPortfolioIdAndTicker(username, portfolioId, ticker.toUpperCase())
                .orElse(new TickerNotes(null, username, portfolioId, ticker.toUpperCase(), "", LocalDate.now()));
    }

    @Override
    public TickerNotes setNoteForTicker(String username, String portfolioId, String ticker, String note) {
        String normalizedTicker = ticker.toUpperCase();

        String trimmed = note == null ? "" : note.trim();
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            trimmed = trimmed.substring(0, MAX_NOTE_LENGTH);
        }

        Optional<TickerNotes> existing = tickerNotesRepository.findByUsernameAndPortfolioIdAndTicker(username, portfolioId, normalizedTicker);

        if (trimmed.isEmpty()) {
            existing.ifPresent(tickerNotesRepository::delete);
            return new TickerNotes(null, username, portfolioId, normalizedTicker, "", LocalDate.now());
        }

        String finalNote = trimmed;
        TickerNotes doc = existing.orElseGet(() ->
                new TickerNotes(UUID.randomUUID().toString(), username, portfolioId, normalizedTicker, finalNote, LocalDate.now()));

        doc.setNote(trimmed);
        doc.setUpdatedAt(LocalDate.now());

        return tickerNotesRepository.save(doc);
    }
}
