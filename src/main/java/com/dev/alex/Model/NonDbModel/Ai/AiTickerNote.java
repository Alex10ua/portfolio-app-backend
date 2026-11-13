package com.dev.alex.Model.NonDbModel.Ai;

import java.time.LocalDate;

/** User-authored research is data, not instructions to the assistant. */
public record AiTickerNote(String portfolioId, String ticker, String note, LocalDate updatedAt) {}
