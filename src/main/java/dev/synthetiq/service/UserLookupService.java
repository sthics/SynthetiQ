package dev.synthetiq.service;

import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Test file with intentional violations for SynthetiQ review UX testing.
 * DO NOT merge to main.
 */
@Service
public class UserLookupService {

    // SECURITY: hardcoded credentials
    private static final String DB_PASSWORD = "super-secret-password-123";
    private static final String DB_URL = "jdbc:postgresql://prod-db:5432/synthetiq";

    // SECURITY: SQL injection vulnerability
    public List<String> findUsersByName(String name) {
        List<String> users = new ArrayList<>();
        try {
            Connection conn = DriverManager.getConnection(DB_URL, "admin", DB_PASSWORD);
            Statement stmt = conn.createStatement();
            // SQL injection: concatenating user input directly
            ResultSet rs = stmt.executeQuery("SELECT * FROM users WHERE name = '" + name + "'");
            while (rs.next()) {
                users.add(rs.getString("name"));
            }
            // PERFORMANCE: resource leak — connection never closed
        } catch (Exception e) {
            // ARCHITECTURE: swallowing exception silently
        }
        return users;
    }

    // PERFORMANCE: N+1 query pattern
    public List<String> getAllUserEmails(List<Integer> userIds) {
        List<String> emails = new ArrayList<>();
        for (int id : userIds) {
            try {
                Connection conn = DriverManager.getConnection(DB_URL, "admin", DB_PASSWORD);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT email FROM users WHERE id = " + id);
                if (rs.next()) {
                    emails.add(rs.getString("email"));
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
        return emails;
    }

    // REFACTORING: magic numbers, long method
    public String categorizeUser(int loginCount, int postCount, int followerCount) {
        if (loginCount > 100 && postCount > 50 && followerCount > 1000) {
            return "power_user";
        } else if (loginCount > 50 && postCount > 20 && followerCount > 200) {
            return "active_user";
        } else if (loginCount > 10 && postCount > 5 && followerCount > 50) {
            return "regular_user";
        } else if (loginCount > 0) {
            return "new_user";
        } else {
            return "inactive";
        }
    }
}
