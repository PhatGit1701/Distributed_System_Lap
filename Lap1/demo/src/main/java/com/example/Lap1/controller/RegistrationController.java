package com.example.Lap1.controller;

import com.example.Lap1.dto.RegistrationRequest;
import com.example.Lap1.dto.RegistrationResponse;
import com.example.Lap1.service.LatencyService;
import com.example.Lap1.service.RegistrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class RegistrationController {

    private final RegistrationService registrationService;
    private final LatencyService latencyService;

    public RegistrationController(RegistrationService registrationService, LatencyService latencyService) {
        this.registrationService = registrationService;
        this.latencyService = latencyService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegistrationResponse> register(@RequestBody RegistrationRequest request) {
        // Simulate configured WAN latency on backend
        latencyService.simulateLatency();

        // Perform server-side validation
        RegistrationResponse response = registrationService.validateAndRegister(request);

        if (!response.isSuccess()) {
            return ResponseEntity.badRequest().body(response);
        }

        return ResponseEntity.ok(response);
    }
}
