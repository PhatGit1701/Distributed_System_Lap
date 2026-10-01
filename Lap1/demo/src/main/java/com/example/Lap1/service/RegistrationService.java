package com.example.Lap1.service;

import com.example.Lap1.dto.RegistrationRequest;
import com.example.Lap1.dto.RegistrationResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class RegistrationService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    public RegistrationResponse validateAndRegister(RegistrationRequest request) {
        long startTime = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();

        if (request == null) {
            errors.add("Registration payload is required.");
            return buildResponse(false, "Validation failed", errors, System.currentTimeMillis() - startTime);
        }

        // 1. Username validation
        String username = request.getUsername();
        if (username == null || username.trim().isEmpty()) {
            errors.add("Username is required.");
        } else if (username.trim().length() < 3) {
            errors.add("Username must be at least 3 characters long.");
        }

        // 2. Email validation
        String email = request.getEmail();
        if (email == null || email.trim().isEmpty()) {
            errors.add("Email is required.");
        } else if (!EMAIL_PATTERN.matcher(email.trim()).matches()) {
            errors.add("Email format is invalid.");
        }

        // 3. Password validation
        String password = request.getPassword();
        if (password == null || password.isEmpty()) {
            errors.add("Password is required.");
        } else if (password.length() < 8) {
            errors.add("Password must be at least 8 characters long.");
        }

        // 4. Confirm Password validation
        String confirmPassword = request.getConfirmPassword();
        if (confirmPassword == null || confirmPassword.isEmpty()) {
            errors.add("Confirm Password is required.");
        } else if (password != null && !password.equals(confirmPassword)) {
            errors.add("Password and Confirm Password do not match.");
        }

        long processingTime = System.currentTimeMillis() - startTime;

        if (!errors.isEmpty()) {
            return buildResponse(false, "Server validation failed", errors, processingTime);
        }

        return buildResponse(true, "Registration successful", errors, processingTime);
    }

    private RegistrationResponse buildResponse(boolean success, String message, List<String> errors, long processingTimeMs) {
        return RegistrationResponse.builder()
                .success(success)
                .message(message)
                .errors(errors)
                .serverProcessingTimeMs(processingTimeMs)
                .build();
    }
}
