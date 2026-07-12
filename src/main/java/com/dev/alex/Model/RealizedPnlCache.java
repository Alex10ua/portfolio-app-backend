package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Stored result of the FIFO realized-P&L computation, one doc per portfolio.
 * Evicted on every transaction write (create/update/delete/import) and
 * recomputed lazily on the next read.
 */
@Document(collection = "realizedPnlCache")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class RealizedPnlCache {
    @Id
    private String portfolioId;
    private Map<String, BigDecimal> realizedByCurrency;
    private LocalDateTime updatedAt;
}
