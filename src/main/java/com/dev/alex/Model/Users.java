package com.dev.alex.Model;

import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonProperty;

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
    private String displayName;
    // WRITE_ONLY: accepted in request bodies (register sends the raw password under
    // this name), but never serialized into any response — closes the hash leak
    // even if a future endpoint returns the raw entity
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String passwordHash;
    private Date createdAt;
    private Date updatedAt;

}
