package com.dev.alex.Model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Per-user UI settings, one doc per user (_id = username). The backend never
 * interprets tableConfig — the frontend's mergeColumns owns that schema, so
 * entries stay opaque maps and column changes need no backend release.
 */
@Document(collection = "userSettings")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class UserSettings {
    @Id
    private String username;
    private String theme; // "light" | "dark" | null (null = follow OS preference)
    private Map<String, PortfolioSettings> portfolioSettings;
    private Date updatedAt;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PortfolioSettings {
        private List<Map<String, Object>> tableConfig;
        private String chartStartMonth; // 'YYYY-MM'
    }
}
