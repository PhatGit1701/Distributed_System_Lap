package com.example.Lap1.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

@Service
public class LatencyService {

    @Value("${app.simulated-latency-ms:1000}")
    private long initialLatencyMs;

    private final AtomicLong currentLatencyMs = new AtomicLong(1000);

    @PostConstruct
    public void init() {
        currentLatencyMs.set(initialLatencyMs);
    }

    public long getLatencyMs() {
        return currentLatencyMs.get();
    }

    public void setLatencyMs(long latencyMs) {
        if (latencyMs < 0) {
            latencyMs = 0;
        }
        currentLatencyMs.set(latencyMs);
    }

    public void simulateLatency() {
        long delay = currentLatencyMs.get();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
