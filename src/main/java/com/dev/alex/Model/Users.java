package com.dev.alex.Model;

import java.util.Date;

import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document("Users")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Users {

    String userId;

    public Users(String userId) {
        this.userId = userId;
    }

    String username;
    String email;
    String passwordHash;
    Date createdAt;
    Date updatedAt;

}
