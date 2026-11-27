# Lumi Cloud BFF Gateway (`lumi-gateway`)

Zero-dependency Node.js 20+/24 BFF proxy for Lumi Android Camera so Release clients never store vendor API keys on-device.

## Features
- **Keyless Mobile Client**: Injects server-side `PRIMARY_API_KEY` / `FALLBACK_API_KEY` into upstream OpenAI-compatible VLM calls.
- **Primary → Fallback Auto Failover**: Automatically fails over on HTTP `408 / 429 / 5xx`, network error, or timeout.
- **Abuse Protection**: Per-device/IP rate limiter (`429 Retry-After`), 4 MB body size cap (`413`), optional `GATEWAY_APP_TOKEN` check (`401`).
- **Privacy-Safe Logging**: Logs only client version, upstream name, failover flag, HTTP status, and latency (never logs image payloads or API keys).

## Local Run & Test
```bash
cd server/lumi-gateway
npm test

PRIMARY_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1" \
PRIMARY_MODEL="qwen-vl-max" \
PRIMARY_API_KEY="sk-your-key" \
npm start
```

## Connecting Lumi Android Release Build
Add the gateway URL to CI environment variables (or untracked `local.properties`):
```properties
LUMI_GATEWAY_URL=https://gateway.lumi.camera
LUMI_GATEWAY_MODEL=lumi-director
```
Build Release APK / Play Store AAB:
```bash
./gradlew :app:assembleRelease   # slim per-ABI APKs (arm64-v8a / armeabi-v7a)
./gradlew :app:bundleRelease     # Google Play AAB bundle
```
