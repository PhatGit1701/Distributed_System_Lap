package com.example.Lap1.controller;

import com.example.Lap1.dto.LatencyConfigDto;
import com.example.Lap1.service.LatencyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/config")
@CrossOrigin(origins = "*")
public class ConfigController {

    private final LatencyService latencyService;

    public ConfigController(LatencyService latencyService) {
        this.latencyService = latencyService;
    }

    @GetMapping("/latency")
    public ResponseEntity<LatencyConfigDto> getLatency() {
        return ResponseEntity.ok(new LatencyConfigDto(latencyService.getLatencyMs()));
    }

    @PutMapping("/latency")
    public ResponseEntity<LatencyConfigDto> updateLatency(@RequestBody LatencyConfigDto config) {
        latencyService.setLatencyMs(config.getLatencyMs());
        return ResponseEntity.ok(new LatencyConfigDto(latencyService.getLatencyMs()));
    }
}
