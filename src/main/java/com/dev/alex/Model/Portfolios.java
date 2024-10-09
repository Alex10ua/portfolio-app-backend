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
    String portfolioId;
    Users userId;
    String portfolioName;
    String description;
    Date createdAt;
    Date updatedAt;

}
