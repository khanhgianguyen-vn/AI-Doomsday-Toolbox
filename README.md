# Gemma Server — Android LLM Node

Android app that runs **Gemma 4 E2B** locally via `llama.cpp` and exposes an **OpenAI-compatible HTTP API** on port 8080. Designed to be deployed on 20 Samsung Galaxy S20 phones, orchestrated via **Tailscale** mesh VPN.

## Quick Start

### 1. Install Tailscale
- Install [Tailscale](https://play.google.com/store/apps/details?id=com.tailscale.ipn) on each phone + orchestrator machine
- Login to the same tailnet on all devices
- Each phone gets a stable IP like `100.x.y.z`

### 2. Install the APK
```bash
adb install gemma-server-debug.apk
```

### 3. Copy the Model
Download `gemma-4-E2B-it-Q4_K_M.gguf` from [ggml-org/gemma-4-E2B-it-GGUF](https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF) and push to each phone:
```bash
adb push gemma-4-E2B-it-Q4_K_M.gguf /sdcard/Android/data/com.llmnode.gemmaserver/files/models/gemma-4-E2B-it-Q4_K_M.gguf
```

### 4. Start the Server
1. Open the app
2. Tap **"Load Gemma 4 E2B"** — wait for model loading (~30-60s)
3. Server auto-starts on port 8080
4. Note the **API Key** shown on screen (tap eye icon to reveal, copy icon to copy)

### 5. Get the Tailscale IP
The app displays the Tailscale IP (e.g., `100.64.0.5`). The full endpoint is:
```
http://100.64.0.5:8080
```

### 6. Test with cURL

**Health check:**
```bash
curl http://100.64.0.5:8080/health
```

**Chat completion:**
```bash
curl -X POST http://100.64.0.5:8080/v1/chat/completions \
  -H "Authorization: Bearer gsk-YourApiKeyHere" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-4-E2B",
    "messages": [{"role": "user", "content": "Hello, how are you?"}],
    "stream": true
  }'
```

**Non-streaming:**
```bash
curl -X POST http://100.64.0.5:8080/v1/chat/completions \
  -H "Authorization: Bearer gsk-YourApiKeyHere" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-4-E2B",
    "messages": [{"role": "user", "content": "Explain quantum computing in simple terms"}],
    "stream": false
  }'
```

## Architecture

```
┌─────────────────────────────────────────┐
│  Android App (Foreground Service)       │
│                                         │
│  ┌───────────┐     ┌────────────────┐   │
│  │ ApiServer  │────▶│ llama-server   │   │
│  │ (NanoHTTPD)│     │ (subprocess)   │   │
│  │ :8080     │     │ :8081 internal │   │
│  │ + Auth    │     │ + Model loaded │   │
│  └───────────┘     └────────────────┘   │
│       ▲                                  │
│       │ Tailscale VPN (100.x.y.z)       │
└───────┼─────────────────────────────────┘
        │
  Orchestrator calls via tailnet
```

## Requirements
- **Device**: Samsung Galaxy S20 (or any arm64-v8a with ≥ 6GB RAM)
- **Android**: 9.0+ (API 28)
- **Model**: Gemma 4 E2B Q4_K_M (~3GB)
- **Network**: Tailscale VPN app installed and connected

## API Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check, returns `{"status":"ok","model":"gemma-4-E2B","busy":false}` |
| `/v1/chat/completions` | POST | OpenAI-compatible chat completion (supports `stream: true`) |

**Auth**: All endpoints except `/health` require `Authorization: Bearer <API_KEY>` header.

**Rate limit**: Max 3 concurrent requests. Additional requests return `429 Too Many Requests`.

## Battery Optimization
The app will prompt you to disable battery optimization. This is required to keep the server running when the screen is off.

## Logs
Server logs are written to:
```
/sdcard/Android/data/com.llmnode.gemmaserver/files/logs/server.log
```
Logs rotate at 5MB.
