package com.dev.alex.Service;

import com.dev.alex.Model.Transactions;
import com.dev.alex.Repository.TransactionsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** The transaction and retry key are inserted together: no reservation can outlive a failed save. */
@Slf4j
@Service
public class TransactionCreationService {
    @Autowired
    private TransactionsRepository transactionsRepository;
    @Autowired
    private MongoTemplate mongoTemplate;

    public void prepare(Transactions transaction, String key) {
        if (key != null && !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1-128 letters, digits, '.', '_', ':' or '-'");
        }
        transaction.setIdempotencyKey(key);
        transaction.setCreationRequestHash(key == null ? null : fingerprint(transaction));
        transaction.setCreationHoldingSynced(key == null ? null : false);
    }

    public Optional<Transactions> findReplay(Transactions transaction) {
        if (transaction.getIdempotencyKey() == null) return Optional.empty();
        return transactionsRepository.findByPortfolioIdAndIdempotencyKey(
                transaction.getPortfolioId(), transaction.getIdempotencyKey()).map(existing -> {
            if (!transaction.getCreationRequestHash().equals(existing.getCreationRequestHash())) {
                throw new IdempotencyConflictException();
            }
            return existing;
        });
    }

    public Creation insert(Transactions transaction) {
        try {
            // save() is an upsert; insert() plus the unique index makes concurrent retries safe.
            return new Creation(transactionsRepository.insert(transaction), true);
        } catch (DuplicateKeyException e) {
            Optional<Transactions> replay = findReplay(transaction);
            if (replay.isEmpty()) throw e;
            return new Creation(replay.get(), false);
        }
    }

    public void recordHoldingSync(Transactions transaction, boolean synced) {
        if (transaction.getIdempotencyKey() == null) return;
        try {
            // Never save a stale full document over a concurrent edit, or resurrect a deleted row.
            mongoTemplate.updateFirst(Query.query(Criteria.where("transactionId").is(transaction.getTransactionId())),
                    Update.update("creationHoldingSynced", synced), Transactions.class);
        } catch (RuntimeException e) {
            // The transaction is already stored. A retry must still return success, conservatively
            // reporting false when completion could not be recorded (or is still in flight).
            log.warn("Could not record holding sync for transaction {}", transaction.getTransactionId(), e);
        }
    }

    private String fingerprint(Transactions t) {
        StringBuilder canonical = new StringBuilder();
        // Exclude server-assigned identity, transient FX and derived totalAmount. When no
        // price/quantity pair exists totalAmount is user input and must be compared too.
        Object[] fields = {t.getDate(), t.getAssetType(), t.getTicker(), t.getName(), t.getTransactionType(),
                t.getQuantity(), t.getPrice(), t.getAmount(), t.getPriceNow(), t.getCurrency(),
                t.getCommission(), t.getImportBatchId(),
                t.getPrice() != null && t.getQuantity() != null ? null : t.getTotalAmount()};
        for (Object field : fields) {
            if (field == null) {
                canonical.append("-1:");
            } else {
                String value = field instanceof BigDecimal number
                        ? number.stripTrailingZeros().toPlainString() : field.toString();
                canonical.append(value.length()).append(':').append(value);
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record Creation(Transactions transaction, boolean inserted) {}

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException() {
            super("Idempotency-Key was already used for different transaction details");
        }
    }
}
