package com.dev.alex.Service.Interface;

import com.dev.alex.Model.TickerNotes;

public interface NoteService {
    TickerNotes getNoteForTicker(String username, String portfolioId, String ticker);
    TickerNotes setNoteForTicker(String username, String portfolioId, String ticker, String note);
}
