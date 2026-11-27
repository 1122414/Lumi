import http from "node:http";

/**
 * Normalizes an OpenAI-compatible base URL into its /chat/completions endpoint.
 */
export function resolveCompletionsUrl(baseUrl) {
  if (!baseUrl || typeof baseUrl !== "string") return null;
  const trimmed = baseUrl.trim().replace(/\/+$/, "");
  if (!trimmed) return null;
  let parsed;
  try {
    parsed = new URL(trimmed);
  } catch {
    return null;
  }
  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") return null;
  if (trimmed.endsWith("/chat/completions")) return trimmed;
  if (parsed.pathname === "" || parsed.pathname === "/") {
    return `${trimmed}/v1/chat/completions`;
  }
  return `${trimmed}/chat/completions`;
}

/**
 * Simple in-memory fixed-window rate limiter keyed by client ID / IP.
 */
export class RateLimiter {
  constructor({ maxRequests = 30, windowMs = 60_000, now = () => Date.now() } = {}) {
    this.maxRequests = maxRequests;
    this.windowMs = windowMs;
    this.now = now;
    this.buckets = new Map();
  }

  check(key) {
    const t = this.now();
    const existing = this.buckets.get(key);
    if (!existing || t - existing.windowStart >= this.windowMs) {
      this.buckets.set(key, { count: 1, windowStart: t });
      if (this.buckets.size > 4096) this.prune(t);
      return { allowed: true, remaining: this.maxRequests - 1, retryAfterSec: 0 };
    }
    if (existing.count >= this.maxRequests) {
      const retryAfterSec = Math.max(1, Math.ceil((this.windowMs - (t - existing.windowStart)) / 1000));
      return { allowed: false, remaining: 0, retryAfterSec };
    }
    existing.count += 1;
    return { allowed: true, remaining: this.maxRequests - existing.count, retryAfterSec: 0 };
  }

  prune(t) {
    for (const [key, bucket] of this.buckets.entries()) {
      if (t - bucket.windowStart >= this.windowMs) {
        this.buckets.delete(key);
      }
    }
  }
}

function sendJson(res, statusCode, body, extraHeaders = {}) {
  const data = Buffer.from(JSON.stringify(body), "utf8");
  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": String(data.length),
    "Cache-Control": "no-store",
    ...extraHeaders,
  });
  res.end(data);
}

async function readBody(req, maxBytes) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > maxBytes) {
      const err = new Error("Payload too large");
      err.code = "PAYLOAD_TOO_LARGE";
      throw err;
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

function shouldFailoverStatus(status) {
  return status === 408 || status === 429 || (status >= 500 && status <= 599);
}

async function callUpstream({ endpoint, apiKey, model, payload, timeoutMs, fetchImpl }) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error("Upstream timeout")), timeoutMs);
  const outgoing = { ...payload, model };
  try {
    const response = await fetchImpl(endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify(outgoing),
      signal: controller.signal,
    });
    const text = await response.text();
    return { ok: response.ok, status: response.status, text };
  } finally {
    clearTimeout(timer);
  }
}

export function createGatewayHandler(config) {
  const {
    primaryBaseUrl,
    primaryModel,
    primaryApiKey,
    fallbackBaseUrl = "",
    fallbackModel = "",
    fallbackApiKey = "",
    appToken = "",
    primaryTimeoutMs = 12_000,
    fallbackTimeoutMs = 12_000,
    maxBodyBytes = 4 * 1024 * 1024,
    rateLimitMax = 30,
    rateLimitWindowMs = 60_000,
    fetchImpl = globalThis.fetch,
    log = (entry) => console.log(JSON.stringify(entry)),
    now = () => Date.now(),
  } = config;

  const primaryEndpoint = resolveCompletionsUrl(primaryBaseUrl);
  const fallbackEndpoint = resolveCompletionsUrl(fallbackBaseUrl);
  const hasPrimary = Boolean(primaryEndpoint && primaryModel && primaryApiKey);
  const hasFallback = Boolean(fallbackEndpoint && fallbackModel && fallbackApiKey);
  const startedAtMs = now();
  const limiter = new RateLimiter({ maxRequests: rateLimitMax, windowMs: rateLimitWindowMs, now });

  return async function handleRequest(req, res) {
    const reqStartMs = now();
    const urlPath = (req.url || "/").split("?")[0];
    const clientVersion = String(req.headers["x-lumi-client"] || "unknown").slice(0, 64);

    if (req.method === "GET" && urlPath === "/healthz") {
      return sendJson(res, 200, {
        status: "ok",
        primaryConfigured: hasPrimary,
        fallbackConfigured: hasFallback,
        uptimeSec: Math.max(0, Math.floor((now() - startedAtMs) / 1000)),
      });
    }

    if (req.method !== "POST" || (urlPath !== "/v1/chat/completions" && urlPath !== "/chat/completions")) {
      return sendJson(res, 404, { error: { message: "Route not found" } });
    }

    if (appToken) {
      const auth = String(req.headers.authorization || "");
      const provided = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
      if (provided !== appToken) {
        return sendJson(res, 401, { error: { message: "Unauthorized client token" } });
      }
    }

    const clientKey = String(
      req.headers["x-lumi-device"] ||
        req.headers["x-forwarded-for"]?.toString().split(",")[0].trim() ||
        req.socket?.remoteAddress ||
        "anon",
    );
    const rate = limiter.check(clientKey);
    if (!rate.allowed) {
      return sendJson(
        res,
        429,
        { error: { message: "Rate limit exceeded. Please retry shortly." } },
        { "Retry-After": String(rate.retryAfterSec) },
      );
    }

    if (!hasPrimary) {
      return sendJson(res, 503, { error: { message: "Gateway upstream not configured" } });
    }

    let rawBody;
    try {
      rawBody = await readBody(req, maxBodyBytes);
    } catch (err) {
      if (err?.code === "PAYLOAD_TOO_LARGE") {
        return sendJson(res, 413, { error: { message: "Request body exceeds maximum allowed size" } });
      }
      return sendJson(res, 400, { error: { message: "Unable to read request body" } });
    }

    let payload;
    try {
      payload = JSON.parse(rawBody);
      if (!payload || typeof payload !== "object" || !Array.isArray(payload.messages)) {
        return sendJson(res, 400, { error: { message: "Invalid chat completion request: messages[] required" } });
      }
    } catch {
      return sendJson(res, 400, { error: { message: "Malformed JSON body" } });
    }

    let finalResponse = null;
    let usedUpstream = "primary";
    let didFailover = false;

    try {
      const primaryRes = await callUpstream({
        endpoint: primaryEndpoint,
        apiKey: primaryApiKey,
        model: primaryModel,
        payload,
        timeoutMs: primaryTimeoutMs,
        fetchImpl,
      });
      if (primaryRes.ok || !hasFallback || !shouldFailoverStatus(primaryRes.status)) {
        finalResponse = primaryRes;
      } else {
        didFailover = true;
      }
    } catch {
      if (!hasFallback) {
        log({
          ts: new Date(now()).toISOString(),
          client: clientVersion,
          upstream: "primary",
          failover: false,
          status: 504,
          latencyMs: now() - reqStartMs,
        });
        return sendJson(res, 504, { error: { message: "Primary vision model timed out" } });
      }
      didFailover = true;
    }

    if (!finalResponse && hasFallback) {
      usedUpstream = "fallback";
      try {
        finalResponse = await callUpstream({
          endpoint: fallbackEndpoint,
          apiKey: fallbackApiKey,
          model: fallbackModel,
          payload,
          timeoutMs: fallbackTimeoutMs,
          fetchImpl,
        });
      } catch {
        log({
          ts: new Date(now()).toISOString(),
          client: clientVersion,
          upstream: "fallback",
          failover: true,
          status: 504,
          latencyMs: now() - reqStartMs,
        });
        return sendJson(res, 504, { error: { message: "Both primary and fallback vision models timed out" } });
      }
    }

    log({
      ts: new Date(now()).toISOString(),
      client: clientVersion,
      upstream: usedUpstream,
      failover: didFailover,
      status: finalResponse.status,
      latencyMs: now() - reqStartMs,
    });

    const bodyBuf = Buffer.from(finalResponse.text, "utf8");
    res.writeHead(finalResponse.status, {
      "Content-Type": "application/json; charset=utf-8",
      "Content-Length": String(bodyBuf.length),
      "Cache-Control": "no-store",
      "X-Lumi-Upstream": usedUpstream,
    });
    res.end(bodyBuf);
  };
}

export function createGatewayServer(config) {
  return http.createServer(createGatewayHandler(config));
}
