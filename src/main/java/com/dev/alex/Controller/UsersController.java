package com.dev.alex.Controller;

import com.dev.alex.Model.Users;
import com.dev.alex.Repository.UsersRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;


import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UsersController {

    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @PostMapping("/createUser")
    public Users creteNewUser(@RequestBody Users user){
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new IllegalArgumentException("Password is required");
        }
        var uuid = UUID.randomUUID().toString();
        user.setUserId(uuid.concat(user.getUsername()));
        user.setPasswordHash(passwordEncoder.encode(user.getPasswordHash()));
        return usersRepository.save(user);
    }
    @GetMapping("/me")
    public Users getCurrentUser(Authentication authentication) {
        return usersRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

}
