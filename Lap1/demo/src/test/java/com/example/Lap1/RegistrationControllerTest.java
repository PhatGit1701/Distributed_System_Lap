package com.example.Lap1;

import com.example.Lap1.dto.RegistrationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RegistrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testRegisterSuccess() throws Exception {
        RegistrationRequest request = RegistrationRequest.builder()
                .username("validUser")
                .email("valid@example.com")
                .password("Password123")
                .confirmPassword("Password123")
                .build();

        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Registration successful"));
    }

    @Test
    void testRegisterBadRequest() throws Exception {
        RegistrationRequest request = RegistrationRequest.builder()
                .username("validUser")
                .email("invalid-email")
                .password("Password123")
                .confirmPassword("Password123")
                .build();

        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors[0]").value("Email format is invalid."));
    }

    @Test
    void testGetLatencyConfig() throws Exception {
        mockMvc.perform(get("/api/config/latency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latencyMs").exists());
    }
}
