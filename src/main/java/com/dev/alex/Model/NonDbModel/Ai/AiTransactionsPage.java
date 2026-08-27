package com.dev.alex.Model.NonDbModel.Ai;

import com.dev.alex.Model.Transactions;

import java.util.List;

/**
 * A page of transactions. Paged because a long-running portfolio has thousands
 * and an unbounded list would swamp a model's context window.
 * <p>
 * Each row carries the {@code fxRate} for its currency, filled in at query time.
 */
public record AiTransactionsPage(
        /** Rows matching the filter before paging. */
        int totalMatching,
        int offset,
        int limit,
        List<Transactions> transactions) {
}
