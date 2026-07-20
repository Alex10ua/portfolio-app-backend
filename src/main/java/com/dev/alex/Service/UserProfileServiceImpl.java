package com.dev.alex.Service;

import com.dev.alex.Model.NonDbModel.ChangePasswordRequest;
import com.dev.alex.Model.NonDbModel.UpdateProfileRequest;
import com.dev.alex.Model.NonDbModel.UserProfileResponse;
import com.dev.alex.Model.UserSettings;
import com.dev.alex.Model.Users;
import com.dev.alex.Repository.UserSettingsRepository;
import com.dev.alex.Repository.UsersRepository;
import com.dev.alex.Service.Interface.UserProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;

@Service
public class UserProfileServiceImpl implements UserProfileService {

    @Autowired
    private UsersRepository usersRepository;
    @Autowired
    private UserSettingsRepository userSettingsRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public UserProfileResponse getProfile(String username) {
        return toResponse(loadUser(username));
    }

    @Override
    public UserProfileResponse updateProfile(String username, UpdateProfileRequest request) {
        Users user = loadUser(username);
        if (request.getEmail() != null) {
            String email = request.getEmail().trim();
            if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new IllegalArgumentException("Invalid email address");
            }
            user.setEmail(email.isEmpty() ? null : email);
        }
        if (request.getDisplayName() != null) {
            String displayName = request.getDisplayName().trim();
            user.setDisplayName(displayName.isEmpty() ? null : displayName);
        }
        user.setUpdatedAt(new Date());
        return toResponse(usersRepository.save(user));
    }

    @Override
    public void changePassword(String username, ChangePasswordRequest request) {
        if (request.getCurrentPassword() == null || request.getNewPassword() == null) {
            throw new IllegalArgumentException("Current and new password are required");
        }
        if (request.getNewPassword().length() < 8) {
            throw new IllegalArgumentException("New password must be at least 8 characters");
        }
        Users user = loadUser(username);
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setUpdatedAt(new Date());
        usersRepository.save(user);
    }

    @Override
    public UserSettings getSettings(String username) {
        // no lazy insert on read — the doc is created by the first PUT
        return userSettingsRepository.findById(username)
                .orElseGet(() -> new UserSettings(username, null, new HashMap<>(), null));
    }

    @Override
    public UserSettings saveSettings(String username, UserSettings settings) {
        settings.setUsername(username); // identity always from the session, never the body
        settings.setUpdatedAt(new Date());
        return userSettingsRepository.save(settings);
    }

    private Users loadUser(String username) {
        return usersRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
    }

    private UserProfileResponse toResponse(Users user) {
        return new UserProfileResponse(user.getUsername(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }
}
