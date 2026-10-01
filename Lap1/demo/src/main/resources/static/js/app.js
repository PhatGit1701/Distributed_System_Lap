// Dynamically resolve Backend API Base URL
// Supports running via Spring Boot (port 8080), IntelliJ IDE Preview (port 63342), or Live Server
const API_BASE_URL = (window.location.port && window.location.port !== '8080')
    ? 'http://localhost:8080'
    : '';

// State Management
let currentMode = 'task1'; // 'task1' | 'task2' | 'task3'
let currentLatencyMs = 1000;
let networkLogs = [];
let benchmarkResults = [];
let savedRequestCount = 0;

const EMAIL_REGEX = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

// Initialize Application
document.addEventListener('DOMContentLoaded', async () => {
    await fetchLatencyConfig();
    updateUIForMode();
});

// Fetch current WAN latency from server
async function fetchLatencyConfig() {
    try {
        const response = await fetch(`${API_BASE_URL}/api/config/latency`);
        if (response.ok) {
            const data = await response.json();
            currentLatencyMs = data.latencyMs;
            updateLatencyDisplay();
        }
    } catch (e) {
        logEvent('Failed to fetch initial latency config', 'error');
    }
}

// Update latency on server
async function updateLatencyOnServer(ms) {
    try {
        const response = await fetch(`${API_BASE_URL}/api/config/latency`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ latencyMs: ms })
        });
        if (response.ok) {
            const data = await response.json();
            currentLatencyMs = data.latencyMs;
            updateLatencyDisplay();
            logEvent(`Simulated WAN Latency updated to ${currentLatencyMs}ms`, 'info');
        }
    } catch (e) {
        logEvent('Failed to update latency config on server', 'error');
    }
}

function updateLatencyDisplay() {
    document.getElementById('currentLatencyDisplay').innerText = `${currentLatencyMs} ms`;
    document.getElementById('customLatencyInput').value = currentLatencyMs;
    document.querySelectorAll('.latency-text').forEach(el => el.innerText = currentLatencyMs);
    
    // Update active state on preset buttons
    document.querySelectorAll('.btn-preset').forEach(btn => {
        const val = parseInt(btn.innerText);
        if (val === currentLatencyMs) {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
    });
}

function setPresetLatency(ms) {
    updateLatencyOnServer(ms);
}

function applyCustomLatency() {
    const val = parseInt(document.getElementById('customLatencyInput').value);
    if (!isNaN(val) && val >= 0) {
        updateLatencyOnServer(val);
    }
}

// Tab Switching
function switchTab(mode) {
    currentMode = mode;
    
    document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(content => content.classList.remove('active'));
    
    if (mode === 'task1') {
        document.getElementById('tabBtnTask1').classList.add('active');
        document.getElementById('formTabContent').classList.add('active');
        updateUIForMode();
    } else if (mode === 'task2') {
        document.getElementById('tabBtnTask2').classList.add('active');
        document.getElementById('formTabContent').classList.add('active');
        updateUIForMode();
    } else if (mode === 'task3') {
        document.getElementById('tabBtnTask3').classList.add('active');
        document.getElementById('task3TabContent').classList.add('active');
    }
}

function updateUIForMode() {
    clearFieldErrors();
    hideResultBanner();
    
    const icon = document.getElementById('formModeIcon');
    const title = document.getElementById('formModeTitle');
    const banner = document.getElementById('modeBanner');
    
    if (currentMode === 'task1') {
        icon.innerText = '🌐';
        title.innerText = 'Task 1 — Traditional Network (Server-Side Validation)';
        banner.className = 'mode-banner task1';
        banner.innerHTML = `<strong>Task 1 Behavior:</strong> Validation is strictly server-side. Every form submit will incur a WAN network round-trip delay (~<span class="latency-text">${currentLatencyMs}</span>ms) before error or success feedback is received. No client JS checks will block submission.`;
    } else if (currentMode === 'task2') {
        icon.innerText = '⚡';
        title.innerText = 'Task 2 — Latency Hiding (Local Client Validation)';
        banner.className = 'mode-banner task2';
        banner.innerHTML = `<strong>Task 2 Behavior:</strong> JavaScript validates input locally <em>before</em> sending HTTP requests. Invalid data shows error immediately (0ms WAN delay, request count = 0). Valid data is sent to backend for secondary validation.`;
    }
}

// Quick Form Presets
function fillPreset(type) {
    clearFieldErrors();
    hideResultBanner();
    
    if (type === 'valid') {
        document.getElementById('username').value = 'alex_dev';
        document.getElementById('email').value = 'alex@example.com';
        document.getElementById('password').value = 'Password123!';
        document.getElementById('confirmPassword').value = 'Password123!';
    } else if (type === 'invalid_email') {
        document.getElementById('username').value = 'alex_dev';
        document.getElementById('email').value = 'invalid-email-format';
        document.getElementById('password').value = 'Password123!';
        document.getElementById('confirmPassword').value = 'Password123!';
    } else if (type === 'short_pass') {
        document.getElementById('username').value = 'alex_dev';
        document.getElementById('email').value = 'alex@example.com';
        document.getElementById('password').value = 'short';
        document.getElementById('confirmPassword').value = 'short';
    } else if (type === 'mismatch') {
        document.getElementById('username').value = 'alex_dev';
        document.getElementById('email').value = 'alex@example.com';
        document.getElementById('password').value = 'Password123!';
        document.getElementById('confirmPassword').value = 'Different123!';
    }
}

function clearForm() {
    document.getElementById('username').value = '';
    document.getElementById('email').value = '';
    document.getElementById('password').value = '';
    document.getElementById('confirmPassword').value = '';
    clearFieldErrors();
    hideResultBanner();
}

// Local Validation Function for Task 2
function validateLocal(username, email, password, confirmPassword) {
    const errors = [];
    const fieldErrors = {};

    if (!username || username.trim() === '') {
        errors.push('Username is required.');
        fieldErrors.username = 'Username is required.';
    } else if (username.trim().length < 3) {
        errors.push('Username must be at least 3 characters long.');
        fieldErrors.username = 'Username must be at least 3 characters long.';
    }

    if (!email || email.trim() === '') {
        errors.push('Email is required.');
        fieldErrors.email = 'Email is required.';
    } else if (!EMAIL_REGEX.test(email.trim())) {
        errors.push('Email format is invalid.');
        fieldErrors.email = 'Email format is invalid.';
    }

    if (!password) {
        errors.push('Password is required.');
        fieldErrors.password = 'Password is required.';
    } else if (password.length < 8) {
        errors.push('Password must be at least 8 characters long.');
        fieldErrors.password = 'Password must be at least 8 characters long.';
    }

    if (!confirmPassword) {
        errors.push('Confirm Password is required.');
        fieldErrors.confirmPassword = 'Confirm Password is required.';
    } else if (password && password !== confirmPassword) {
        errors.push('Password and Confirm Password do not match.');
        fieldErrors.confirmPassword = 'Password and Confirm Password do not match.';
    }

    return {
        valid: errors.length === 0,
        errors,
        fieldErrors
    };
}

// Form Submission Handler
async function handleFormSubmit(event) {
    event.preventDefault();
    clearFieldErrors();
    hideResultBanner();

    const username = document.getElementById('username').value;
    const email = document.getElementById('email').value;
    const password = document.getElementById('password').value;
    const confirmPassword = document.getElementById('confirmPassword').value;

    const payload = { username, email, password, confirmPassword };
    const submitBtn = document.getElementById('submitBtn');
    const submitBtnText = document.getElementById('submitBtnText');

    const startTime = performance.now();

    logEvent(`----------------------------------------`, 'info');
    logEvent(`[${currentMode.toUpperCase()}] Registration form submitted`, 'info');

    if (currentMode === 'task1') {
        // TASK 1: TRADITIONAL NETWORK (NO CLIENT VALIDATION BEFORE SUBMIT)
        logEvent(`[Task 1] Skipping client validation. Sending HTTP POST ${API_BASE_URL}/api/register...`, 'warning');
        logEvent(`[Task 1] Waiting for simulated WAN delay (~${currentLatencyMs}ms)...`, 'info');
        
        submitBtn.disabled = true;
        submitBtnText.innerHTML = `<span class="spinner"></span> Waiting for server (${currentLatencyMs}ms WAN latency)...`;

        try {
            const response = await fetch(`${API_BASE_URL}/api/register`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            const endTime = performance.now();
            const durationMs = Math.round(endTime - startTime);
            const data = await response.json();

            if (response.ok && data.success) {
                logEvent(`[Task 1] Server Response: HTTP 200 OK (${durationMs}ms RTT)`, 'success');
                logEvent(`[Task 1] Registration successful!`, 'success');
                showResultBanner(true, 'Registration Successful!', [`Server response time: ${durationMs} ms`]);
                addNetworkRow('Task 1', 'YES', 'SERVER', `${durationMs} ms`, 'Passed (HTTP 200)');
            } else {
                logEvent(`[Task 1] Server Response: HTTP ${response.status} Bad Request (${durationMs}ms RTT)`, 'error');
                logEvent(`[Task 1] Server validation failed: ${data.errors.join(' | ')}`, 'error');
                showResultBanner(false, 'Server Validation Failed!', data.errors);
                addNetworkRow('Task 1', 'YES', 'SERVER', `${durationMs} ms`, 'Failed (HTTP 400)');
            }
        } catch (err) {
            const endTime = performance.now();
            const durationMs = Math.round(endTime - startTime);
            logEvent(`[Task 1] Network error: ${err.message}`, 'error');
            showResultBanner(false, 'Network Error', [err.message]);
            addNetworkRow('Task 1', 'YES', 'SERVER', `${durationMs} ms`, 'Network Error');
        } finally {
            submitBtn.disabled = false;
            submitBtnText.innerText = 'Submit Registration';
        }

    } else if (currentMode === 'task2') {
        // TASK 2: LATENCY HIDING (LOCAL VALIDATION FIRST)
        logEvent(`[Task 2] Executing local JavaScript validation...`, 'info');
        
        const localResult = validateLocal(username, email, password, confirmPassword);

        if (!localResult.valid) {
            const endTime = performance.now();
            const durationMs = Math.round(endTime - startTime);
            
            savedRequestCount++;
            document.getElementById('metricRequestsSaved').innerText = savedRequestCount;

            logEvent(`[Task 2] ❌ Local validation failed: ${localResult.errors.join(' | ')}`, 'error');
            logEvent(`[Task 2] ⛔ HTTP request BLOCKED. Request count = 0. Total time: ${durationMs}ms`, 'blocked');
            
            showFieldErrors(localResult.fieldErrors);
            showResultBanner(false, 'Local Validation Failed (Request Not Sent)', localResult.errors);
            addNetworkRow('Task 2', 'NO (Blocked)', 'CLIENT', `${durationMs} ms`, 'Failed (Local JS)');
        } else {
            logEvent(`[Task 2] ✅ Local validation passed!`, 'success');
            logEvent(`[Task 2] Sending HTTP request to server for mandatory server validation...`, 'warning');
            
            submitBtn.disabled = true;
            submitBtnText.innerHTML = `<span class="spinner"></span> Contacting server (${currentLatencyMs}ms)...`;

            try {
                const response = await fetch(`${API_BASE_URL}/api/register`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });

                const endTime = performance.now();
                const durationMs = Math.round(endTime - startTime);
                const data = await response.json();

                if (response.ok && data.success) {
                    logEvent(`[Task 2] Server Response: HTTP 200 OK (${durationMs}ms RTT)`, 'success');
                    logEvent(`[Task 2] Registration completed successfully!`, 'success');
                    showResultBanner(true, 'Registration Successful (Client + Server Pass)!', [`Total time: ${durationMs} ms`]);
                    addNetworkRow('Task 2', 'YES', 'CLIENT+SERVER', `${durationMs} ms`, 'Passed (HTTP 200)');
                } else {
                    logEvent(`[Task 2] Server rejected request: ${data.errors.join(' | ')}`, 'error');
                    showResultBanner(false, 'Server Validation Rejected Payload', data.errors);
                    addNetworkRow('Task 2', 'YES', 'SERVER', `${durationMs} ms`, 'Failed (HTTP 400)');
                }
            } catch (err) {
                logEvent(`[Task 2] Network error: ${err.message}`, 'error');
            } finally {
                submitBtn.disabled = false;
                submitBtnText.innerText = 'Submit Registration';
            }
        }
    }
}

// UI Helpers for Errors & Banners
function showFieldErrors(fieldErrors) {
    for (const [field, msg] of Object.entries(fieldErrors)) {
        const inputEl = document.getElementById(field);
        const errEl = document.getElementById(`err-${field}`);
        if (inputEl) inputEl.classList.add('is-invalid');
        if (errEl) {
            errEl.innerText = msg;
            errEl.classList.add('show');
        }
    }
}

function clearFieldErrors() {
    ['username', 'email', 'password', 'confirmPassword'].forEach(field => {
        const inputEl = document.getElementById(field);
        const errEl = document.getElementById(`err-${field}`);
        if (inputEl) {
            inputEl.classList.remove('is-invalid');
            inputEl.classList.remove('is-valid');
        }
        if (errEl) {
            errEl.innerText = '';
            errEl.classList.remove('show');
        }
    });
}

function showResultBanner(isSuccess, title, errors = []) {
    const banner = document.getElementById('resultBanner');
    const status = document.getElementById('resultStatus');
    const details = document.getElementById('resultDetails');

    banner.className = `result-banner show ${isSuccess ? 'success' : 'error'}`;
    status.innerText = title;
    
    if (errors.length > 0) {
        details.innerHTML = errors.map(e => `<div>• ${e}</div>`).join('');
    } else {
        details.innerHTML = '';
    }
}

function hideResultBanner() {
    const banner = document.getElementById('resultBanner');
    banner.className = 'result-banner';
}

function logEvent(msg, type = 'info') {
    const consoleEl = document.getElementById('logConsole');
    const now = new Date().toTimeString().split(' ')[0];
    
    const entry = document.createElement('div');
    entry.className = `log-entry log-${type}`;
    entry.innerHTML = `<span class="log-time">[${now}]</span> ${msg}`;
    
    consoleEl.appendChild(entry);
    consoleEl.scrollTop = consoleEl.scrollHeight;
}

function clearLogs() {
    document.getElementById('logConsole').innerHTML = '<div class="log-entry log-info">[Logs cleared]</div>';
}

function addNetworkRow(mode, sent, location, rtt, result) {
    const tbody = document.querySelector('#networkTable tbody');
    const emptyRow = document.getElementById('emptyNetRow');
    if (emptyRow) emptyRow.remove();

    const tr = document.createElement('tr');
    const sentBadgeClass = sent.startsWith('YES') ? 'badge-sent' : 'badge-blocked';
    const locBadgeClass = location.includes('CLIENT') ? 'badge-client' : 'badge-server';
    const resBadgeClass = result.startsWith('Passed') ? 'badge-pass' : 'badge-fail';

    tr.innerHTML = `
        <td><span class="badge ${locBadgeClass}">${mode}</span></td>
        <td><span class="badge ${sentBadgeClass}">${sent}</span></td>
        <td>${location}</td>
        <td><strong>${rtt}</strong></td>
        <td><span class="badge ${resBadgeClass}">${result}</span></td>
    `;
    tbody.insertBefore(tr, tbody.firstChild);
}

// TASK 3: AUTOMATED BENCHMARK SUITE
async function runAutomatedBenchmark() {
    const testCases = [
        {
            name: "Test Case 1: Invalid Email",
            payload: { username: "alex_dev", email: "invalid-email", password: "Password123!", confirmPassword: "Password123!" }
        },
        {
            name: "Test Case 2: Empty Username",
            payload: { username: "", email: "alex@example.com", password: "Password123!", confirmPassword: "Password123!" }
        },
        {
            name: "Test Case 3: Password Too Short",
            payload: { username: "alex_dev", email: "alex@example.com", password: "short", confirmPassword: "short" }
        },
        {
            name: "Test Case 4: Password Mismatch",
            payload: { username: "alex_dev", email: "alex@example.com", password: "Password123!", confirmPassword: "Different123!" }
        },
        {
            name: "Test Case 5: Valid Registration",
            payload: { username: "alex_dev", email: "alex@example.com", password: "Password123!", confirmPassword: "Password123!" }
        }
    ];

    const tbody = document.querySelector('#benchmarkTable tbody');
    tbody.innerHTML = '<tr><td colspan="7" style="text-align: center; color: var(--accent-cyan); padding: 1.5rem;"><span class="spinner"></span> Running automated benchmark suite against live server...</td></tr>';

    benchmarkResults = [];
    let task1TotalTime = 0;
    let task1Count = 0;
    let task2TotalTime = 0;
    let task2Count = 0;

    for (const tc of testCases) {
        // Run Task 1 (Server Side)
        const t1Start = performance.now();
        let t1Status = "";
        try {
            const res = await fetch(`${API_BASE_URL}/api/register`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(tc.payload)
            });
            const t1End = performance.now();
            const t1Time = Math.round(t1End - t1Start);
            const data = await res.json();
            t1Status = data.success ? "Passed (200)" : "Failed (400)";

            benchmarkResults.push({
                testCase: tc.name,
                mode: "Task 1 (Server)",
                requestSent: "YES",
                location: "SERVER",
                latency: `${currentLatencyMs}ms`,
                time: `${t1Time} ms`,
                status: t1Status,
                rawTime: t1Time
            });

            if (!data.success) {
                task1TotalTime += t1Time;
                task1Count++;
            }
        } catch (e) {
            // Error handling
        }

        // Run Task 2 (Local Validation)
        const t2Start = performance.now();
        const localCheck = validateLocal(tc.payload.username, tc.payload.email, tc.payload.password, tc.payload.confirmPassword);
        
        if (!localCheck.valid) {
            const t2End = performance.now();
            const t2Time = Math.round(t2End - t2Start);
            benchmarkResults.push({
                testCase: tc.name,
                mode: "Task 2 (Local)",
                requestSent: "NO (Blocked)",
                location: "CLIENT",
                latency: `${currentLatencyMs}ms`,
                time: `${t2Time} ms`,
                status: "Failed (Local JS)",
                rawTime: t2Time
            });

            task2TotalTime += t2Time;
            task2Count++;
        } else {
            // Valid payload in Task 2 -> must send to server
            try {
                const res = await fetch(`${API_BASE_URL}/api/register`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(tc.payload)
                });
                const t2End = performance.now();
                const t2Time = Math.round(t2End - t2Start);
                const data = await res.json();
                
                benchmarkResults.push({
                    testCase: tc.name,
                    mode: "Task 2 (Local)",
                    requestSent: "YES",
                    location: "CLIENT + SERVER",
                    latency: `${currentLatencyMs}ms`,
                    time: `${t2Time} ms`,
                    status: data.success ? "Passed (200)" : "Failed (400)",
                    rawTime: t2Time
                });
            } catch (e) {}
        }
    }

    // Render Benchmark Table
    tbody.innerHTML = '';
    benchmarkResults.forEach(r => {
        const tr = document.createElement('tr');
        const sentBadge = r.requestSent.startsWith('YES') ? 'badge-sent' : 'badge-blocked';
        const locBadge = r.location.includes('CLIENT') ? 'badge-client' : 'badge-server';
        const statusBadge = r.status.includes('Passed') ? 'badge-pass' : 'badge-fail';

        tr.innerHTML = `
            <td><strong>${r.testCase}</strong></td>
            <td>${r.mode}</td>
            <td><span class="badge ${sentBadge}">${r.requestSent}</span></td>
            <td><span class="badge ${locBadge}">${r.location}</span></td>
            <td>${r.latency}</td>
            <td><strong style="color: var(--accent-cyan);">${r.time}</strong></td>
            <td><span class="badge ${statusBadge}">${r.status}</span></td>
        `;
        tbody.appendChild(tr);
    });

    // Update Summary Metrics
    if (task1Count > 0) {
        document.getElementById('metricTask1Time').innerText = `~${Math.round(task1TotalTime / task1Count)} ms`;
    }
    if (task2Count > 0) {
        document.getElementById('metricTask2Time').innerText = `~${Math.round(task2TotalTime / task2Count)} ms`;
    }
}
