package com.dev.alex.Controller;

import com.dev.alex.Model.NonDbModel.ChangePasswordRequest;
import com.dev.alex.Model.NonDbModel.UpdateProfileRequest;
import com.dev.alex.Model.NonDbModel.UserProfileResponse;
import com.dev.alex.Model.UserSettings;
import com.dev.alex.Model.Users;
import com.dev.alex.Repository.UsersRepository;
import com.dev.alex.Service.UserProfileServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UsersController {

    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UserProfileServiceImpl userProfileService;

    @PostMapping("/createUser")
    public UserProfileResponse creteNewUser(@RequestBody Users user){
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new IllegalArgumentException("Password is required");
        }
        if (usersRepository.findByUsername(user.getUsername()).isPresent()) {
            throw new IllegalArgumentException("Username already taken");
        }
        var uuid = UUID.randomUUID().toString();
        user.setUserId(uuid.concat(user.getUsername()));
        user.setPasswordHash(passwordEncoder.encode(user.getPasswordHash()));
        user.setCreatedAt(new Date());
        Users saved = usersRepository.save(user);
        return new UserProfileResponse(saved.getUsername(), saved.getEmail(), saved.getDisplayName(), saved.getCreatedAt());
    }

    @GetMapping("/me")
    public UserProfileResponse getCurrentUser(Authentication authentication) {
        return userProfileService.getProfile(authentication.getName());
    }

    @PutMapping("/me")
    public UserProfileResponse updateProfile(@RequestBody UpdateProfileRequest request,
                                             Authentication authentication) {
        return userProfileService.updateProfile(authentication.getName(), request);
    }

    @PostMapping("/me/password")
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest request,
                                               Authentication authentication) {
        userProfileService.changePassword(authentication.getName(), request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me/settings")
    public UserSettings getSettings(Authentication authentication) {
        return userProfileService.getSettings(authentication.getName());
    }

    @PutMapping("/me/settings")
    public UserSettings saveSettings(@RequestBody UserSettings settings, Authentication authentication) {
        return userProfileService.saveSettings(authentication.getName(), settings);
    }
}
