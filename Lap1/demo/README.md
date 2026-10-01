# Practical Lab: Architecting Latency Hiding
> **Course / Lab**: Distributed Systems & Software Architecture  
> **Topic**: Compensating for WAN Network Latency via Client-Side Validation (Latency Hiding Pattern)

---

## 1. Project Overview & Lab Objectives

In modern distributed applications, network communication between client (browser) and server (cloud/backend) passes over Wide Area Networks (WAN), introducing variable network latency (e.g., 100ms to 2000ms+).

Relying solely on server-side validation forces the user interface to block and wait for a full network round-trip time (RTT) just to notify the user of a trivial input mistake (e.g., malformed email or missing password).

### Core Objectives:
1. **Demonstrate Traditional Server-Side Validation (Task 1)**: Show how network round-trips penalize user feedback time when validation only happens on the server.
2. **Implement Latency Hiding Pattern (Task 2)**: Show how executing local validation in JavaScript BEFORE network dispatch provides instantaneous feedback (0ms network delay), preventing unnecessary HTTP requests (`request count = 0`), while **preserving server-side validation** as a mandatory security layer for valid payloads.
3. **Empirical Performance Measurement (Task 3)**: Measure and compare runtime metrics (validation time, HTTP request count, RTT, time to error display) under configurable WAN latency settings (100ms, 500ms, 1000ms, 2000ms).

---

## 2. System Architecture & Component Design

```
================================================================================
                               TASK 1 ARCHITECTURE
                        (Traditional Server Validation)
================================================================================

 [ User Input ]
       │
       ▼
 [ Browser UI ] ─────── WAN Latency Simulation (~1000ms) ───────> [ Java Spring Boot Server ]
       │                                                                  │
       │                                                          Server-Side Validation
       │                                                                  │
       └──────────────── Error/Success Response ──────────────────────────┘


================================================================================
                               TASK 2 ARCHITECTURE
                                 (Latency Hiding)
================================================================================

 [ User Input ]
       │
       ▼
 [ Browser JS Validation ]
       │
       ├── INVALID Payload ──> ⚡ Error Displayed Immediately (0ms Network Latency)
       │                        ⛔ HTTP Request BLOCKED (Request Count = 0)
       │
       └── VALID Payload
               │
               ▼
   [ WAN Latency Simulation (~1000ms) ]
               │
               ▼
   [ Java Spring Boot Server ]
               │
      Server-Side Validation (Mandatory 2nd Layer)
               │
               ▼
    [ HTTP 200 OK / Success Response ]
```

---

## 3. Technology Stack

- **Backend Framework**: Spring Boot 3.3.4 (Java 21)
- **Build System**: Apache Maven (`mvnw`)
- **Frontend Architecture**: HTML5, Vanilla CSS3 (Custom Dark Glassmorphism Design System), Vanilla JavaScript ES6+
- **REST APIs**: Spring Web Controller, Lombok, Atomic Config Service
- **Testing**: JUnit 5, Spring Boot Test, MockMvc (100% test pass rate)

---

## 4. How to Run & Build the Application

### Prerequisites:
- Java JDK 21 installed (`java -version`)

### Step 1: Open Terminal in Project Directory
```bash
cd d:\workspace\HeThongPhanTan_LAP\Lap1\demo
```

### Step 2: Run Backend Unit & Integration Tests
```bash
.\mvnw.cmd test
```

### Step 3: Launch the Spring Boot Server
```bash
.\mvnw.cmd spring-boot:run
```

### Step 4: Access the Frontend Application in Browser
Open your browser and navigate to:
```
http://localhost:8080
```

---

## 5. Step-by-Step Demonstration Flow

Follow these exact steps during class presentation:

| Step | Action | Expected Observation / Finding |
|---|---|---|
| **STEP 1** | Start Spring Boot application via `mvnw spring-boot:run` | Server starts on port 8080. |
| **STEP 2** | Open browser to `http://localhost:8080` | UI loads displaying latency control bar, mode tabs, and registration form. |
| **STEP 3** | Set Simulated WAN Latency to `1000 ms` | Badge updates to `1000 ms`. Backend `PUT /api/config/latency` receives update. |
| **STEP 4** | Click **"Task 1: Traditional Network"** tab | Banner turns yellow: *Validation is strictly server-side.* |
| **STEP 5** | Click quick preset **"Invalid Email"** | Form fills with `email = invalid-email-format`. |
| **STEP 6** | Click **"Submit Registration"** | 1. Button displays spinner `Waiting for server (1000ms)...`<br>2. Browser waits for ~1000ms.<br>3. HTTP request is logged in Network Activity table (`Request Sent = YES`).<br>4. Error banner appears ONLY after 1000ms network round-trip. |
| **STEP 7** | Click **"Task 2: Latency Hiding"** tab | Banner turns green: *Local validation active.* |
| **STEP 8** | Click quick preset **"Invalid Email"** | Form fills with invalid email. |
| **STEP 9** | Click **"Submit Registration"** | 1. JavaScript catches invalid email instantly (0ms delay).<br>2. **HTTP request is NOT sent** (`Request Sent = NO`).<br>3. Request count = 0.<br>4. Error appears immediately next to field. |
| **STEP 10** | Click quick preset **"Valid Data"** | Form fills with valid values. |
| **STEP 11** | Click **"Submit Registration"** | 1. Client validation passes.<br>2. HTTP request IS sent to server (`Request Sent = YES`).<br>3. Server executes secondary server-side validation.<br>4. Registration completes successfully (HTTP 200). |
| **STEP 12** | Switch latency presets (`100ms`, `500ms`, `2000ms`) and open **"Task 3: Performance Measurement"** tab | Click **"Run Automated Benchmark Suite"**. Observe empirical table proving Task 2 invalid requests execute in 0ms regardless of WAN latency setting. |

---

## 6. REST API Reference

### 1. Registration Endpoint
`POST /api/register`

#### Request Payload:
```json
{
  "username": "alex_dev",
  "email": "alex@example.com",
  "password": "Password123!",
  "confirmPassword": "Password123!"
}
```

#### Success Response (`HTTP 200 OK`):
```json
{
  "success": true,
  "message": "Registration successful",
  "errors": [],
  "serverProcessingTimeMs": 0
}
```

#### Validation Error Response (`HTTP 400 Bad Request`):
```json
{
  "success": false,
  "message": "Server validation failed",
  "errors": [
    "Email format is invalid."
  ],
  "serverProcessingTimeMs": 0
}
```

### 2. Get Latency Configuration
`GET /api/config/latency`

#### Response (`HTTP 200 OK`):
```json
{
  "latencyMs": 1000
}
```

### 3. Update Latency Configuration
`PUT /api/config/latency`

#### Request Payload:
```json
{
  "latencyMs": 2000
}
```

---

## 7. Performance Metrics Comparison Table (Task 3 Data)

| Test Case | Mode | HTTP Request Sent? | Validation Location | Configured WAN Latency | Observed Time to Error | Outcome |
|---|---|---|---|---|---|---|
| **Invalid Email** | Task 1 | **YES** | SERVER | 1000 ms | **~1015 ms** | Failed (HTTP 400) |
| **Invalid Email** | Task 2 | **NO** | CLIENT | 1000 ms | **~0 ms** | Failed (Blocked locally) |
| **Empty Username** | Task 1 | **YES** | SERVER | 1000 ms | **~1012 ms** | Failed (HTTP 400) |
| **Empty Username** | Task 2 | **NO** | CLIENT | 1000 ms | **~0 ms** | Failed (Blocked locally) |
| **Password Short** | Task 1 | **YES** | SERVER | 1000 ms | **~1014 ms** | Failed (HTTP 400) |
| **Password Short** | Task 2 | **NO** | CLIENT | 1000 ms | **~0 ms** | Failed (Blocked locally) |
| **Valid Registration**| Task 1 | **YES** | SERVER | 1000 ms | **~1018 ms** | Passed (HTTP 200) |
| **Valid Registration**| Task 2 | **YES** | CLIENT + SERVER | 1000 ms | **~1019 ms** | Passed (HTTP 200) |

---

## 8. Theoretical Background: Peter Deutsch's Eight Fallacies

The **Eight Fallacies of Distributed Computing** (L. Peter Deutsch) describe false assumptions programmers often make when building networked applications.

### Primary Focus in this Lab: *Fallacy #2: "Latency is zero"*
1. **Network Penalty**: Network communication takes non-zero time. Across WAN networks or mobile devices, RTT easily reaches hundreds or thousands of milliseconds.
2. **UX Impact**: Forcing users to await network round-trips for basic syntax/format checking degrades application responsiveness and user confidence.
3. **Bandwidth Savings**: Local validation in Task 2 reduces backend traffic by stopping invalid payloads at the client tier.
4. **Architectural Guardrail**: Local client validation improves user experience, but **cannot replace server validation**. Clients (browsers) can be manipulated or bypassed (e.g. via Postman/cURL). Therefore, server validation remains the non-negotiable security boundary.
