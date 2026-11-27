import { createGatewayServer } from "./gateway.mjs";

const port = Number.parseInt(process.env.PORT || "8787", 10);
const server = createGatewayServer({
  primaryBaseUrl: process.env.PRIMARY_BASE_URL || "",
  primaryModel: process.env.PRIMARY_MODEL || "",
  primaryApiKey: process.env.PRIMARY_API_KEY || "",
  fallbackBaseUrl: process.env.FALLBACK_BASE_URL || "",
  fallbackModel: process.env.FALLBACK_MODEL || "",
  fallbackApiKey: process.env.FALLBACK_API_KEY || "",
  appToken: process.env.GATEWAY_APP_TOKEN || "",
  primaryTimeoutMs: Number.parseInt(process.env.PRIMARY_TIMEOUT_MS || "12000", 10),
  fallbackTimeoutMs: Number.parseInt(process.env.FALLBACK_TIMEOUT_MS || "12000", 10),
  rateLimitMax: Number.parseInt(process.env.RATE_LIMIT_MAX || "30", 10),
  rateLimitWindowMs: Number.parseInt(process.env.RATE_LIMIT_WINDOW_MS || "60000", 10),
});

server.listen(port, () => {
  console.log(`[lumi-gateway] listening on :${port}`);
});
