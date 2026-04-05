package com.dev.alex.Model;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("import_batches")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class ImportBatch {
    @Id
    @JsonProperty("id")
    private String batchId;
    private String portfolioId;
    private String filename;
    private LocalDateTime uploadedAt;
    private int transactionCount;
}
