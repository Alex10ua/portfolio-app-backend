package com.dev.alex.Model.NonDbModel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserProfileResponse {
    private String username;
    private String email;
    private String displayName;
    private Date createdAt;
}
