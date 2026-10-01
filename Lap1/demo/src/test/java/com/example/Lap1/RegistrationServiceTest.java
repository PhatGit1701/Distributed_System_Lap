package com.example.Lap1;

import com.example.Lap1.dto.RegistrationRequest;
import com.example.Lap1.dto.RegistrationResponse;
import com.example.Lap1.service.RegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationServiceTest {

    private RegistrationService registrationService;

    @BeforeEach
    void setUp() {
        registrationService = new RegistrationService();
    }

    @Test
    @DisplayName("Should pass registration when all fields are valid")
    void testValidRegistration() {
        RegistrationRequest request = RegistrationRequest.builder()
                .username("john_doe")
                .email("john@example.com")
                .password("Password123!")
                .confirmPassword("Password123!")
                .build();

        RegistrationResponse response = registrationService.validateAndRegister(request);

        assertTrue(response.isSuccess());
        assertEquals("Registration successful", response.getMessage());
        assertTrue(response.getErrors().isEmpty());
    }

    @Test
    @DisplayName("Should fail when username is empty")
    void testEmptyUsername() {
        RegistrationRequest request = RegistrationRequest.builder()
                .username("  ")
                .email("john@example.com")
                .password("Password123!")
                .confirmPassword("Password123!")
                .build();

        RegistrationResponse response = registrationService.validateAndRegister(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrors().contains("Username is required."));
    }

    @Test
    @DisplayName("Should fail when email format is invalid")
    void testInvalidEmail() {
        RegistrationRequest request = RegistrationRequest.builder()
                .username("john_doe")
                .email("invalid-email-format")
                .password("Password123!")
                .confirmPassword("Password123!")
                .build();

        RegistrationResponse response = registrationService.validateAndRegister(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrors().contains("Email format is invalid."));
    }

    @Test
    @DisplayName("Should fail when password is too short")
    void testPasswordTooShort() {
        RegistrationRequest request = RegistrationRequest.builder()
                .username("john_doe")
                .email("john@example.com")
                .password("short")
                .confirmPassword("short")
                .build();

        RegistrationResponse response = registrationService.validateAndRegister(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrors().contains("Password must be at least 8 characters long."));
    }

    @Test
    @DisplayName("Should fail when password confirmation does not match")
    void testPasswordMismatch() {
        RegistrationRequest request = RegistrationRequest.builder()
                .username("john_doe")
                .email("john@example.com")
                .password("Password123!")
                .confirmPassword("DifferentPassword123!")
                .build();

        RegistrationResponse response = registrationService.validateAndRegister(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrors().contains("Password and Confirm Password do not match."));
    }
}
