package com.example.Lap1.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistrationRequest {
    private String username;
    private String email;
    private String password;
    private String confirmPassword;
}
