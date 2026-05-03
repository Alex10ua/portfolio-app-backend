package com.dev.alex.Model;

import java.time.LocalDate;
import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("portfolios")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class Portfolios {
    @Id
    private String portfolioId;
    private String username;
    private String portfolioName;
    private String description;
    private Date createdAt;
    private Date updatedAt;
    private LocalDate firstTradeYear;

    public Portfolios(String portfolioName, String description, Date createdAt, Date updatedAt, LocalDate firstTradeYear) {
        this.portfolioName = portfolioName;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.firstTradeYear = firstTradeYear;
    }
}
