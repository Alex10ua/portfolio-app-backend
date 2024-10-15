package com.dev.alex.Model;

import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("users")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Users {
    @Id
    private String userId;

    public Users(String userId) {
        this.userId = userId;
    }

    private String username;
    private String email;
    private String passwordHash;
    private Date createdAt;
    private Date updatedAt;

}
