package com.dev.alex.Service.Interface;

import com.dev.alex.Model.NonDbModel.ChangePasswordRequest;
import com.dev.alex.Model.NonDbModel.UpdateProfileRequest;
import com.dev.alex.Model.NonDbModel.UserProfileResponse;
import com.dev.alex.Model.UserSettings;

public interface UserProfileService {
    UserProfileResponse getProfile(String username);
    UserProfileResponse updateProfile(String username, UpdateProfileRequest request);
    void changePassword(String username, ChangePasswordRequest request);
    UserSettings getSettings(String username);
    UserSettings saveSettings(String username, UserSettings settings);
}
