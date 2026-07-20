package com.dev.alex.Repository;

import com.dev.alex.Model.UserSettings;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSettingsRepository extends MongoRepository<UserSettings, String> {
}
