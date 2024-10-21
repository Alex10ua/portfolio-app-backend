package com.dev.alex.Model;

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
    //private Users userId;
    private String portfolioName;
    private String description;
    private Date createdAt;
    private Date updatedAt;

    public Portfolios(String portfolioName, String description, Date createdAt, Date updatedAt) {
        this.portfolioName = portfolioName;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
