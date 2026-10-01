package com.example.Lap1.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistrationResponse {
    private boolean success;
    private String message;
    private List<String> errors;
    private long serverProcessingTimeMs;
}
