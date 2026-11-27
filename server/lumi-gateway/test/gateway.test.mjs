import test from "node:test";
import assert from "node:assert/strict";
import { createGatewayServer, resolveCompletionsUrl } from "../src/gateway.mjs";

async function startServer(config) {
  const server = createGatewayServer({ log: () => {}, ...config });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

test("resolveCompletionsUrl normalizes root and /v1 paths", () => {
  assert.equal(resolveCompletionsUrl("https://api.example.com"), "https://api.example.com/v1/chat/completions");
  assert.equal(resolveCompletionsUrl("https://api.example.com/v1/"), "https://api.example.com/v1/chat/completions");
  assert.equal(
    resolveCompletionsUrl("https://api.example.com/v1/chat/completions"),
    "https://api.example.com/v1/chat/completions",
  );
  assert.equal(resolveCompletionsUrl("ftp://bad.example.com"), null);
});

test("healthz reports gateway configuration", async () => {
  const srv = await startServer({
    primaryBaseUrl: "https://primary.example/v1",
    primaryModel: "vlm-primary",
    primaryApiKey: "sk-primary-secret",
    fallbackBaseUrl: "https://fallback.example/v1",
    fallbackModel: "vlm-fallback",
    fallbackApiKey: "sk-fallback-secret",
  });
  try {
    const res = await fetch(`${srv.baseUrl}/healthz`);
    assert.equal(res.status, 200);
    const json = await res.json();
    assert.equal(json.status, "ok");
    assert.equal(json.primaryConfigured, true);
    assert.equal(json.fallbackConfigured, true);
  } finally {
    await srv.close();
  }
});

test("forwards chat completion with server-injected API key and model name", async () => {
  const seen = [];
  const srv = await startServer({
    primaryBaseUrl: "https://primary.example/v1",
    primaryModel: "vlm-server-model",
    primaryApiKey: "sk-server-only-key",
    fetchImpl: async (url, init) => {
      seen.push({ url, headers: init.headers, body: JSON.parse(init.body) });
      return new Response(JSON.stringify({ choices: [{ message: { content: "{\"ok\":true}" } }] }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    },
  });
  try {
    const res = await fetch(`${srv.baseUrl}/v1/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Lumi-Client": "android/0.5.0",
      },
      body: JSON.stringify({ model: "lumi-director", messages: [{ role: "user", content: "hello" }] }),
    });
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("x-lumi-upstream"), "primary");
    assert.equal(seen.length, 1);
    assert.equal(seen[0].url, "https://primary.example/v1/chat/completions");
    assert.equal(seen[0].headers.Authorization, "Bearer sk-server-only-key");
    assert.equal(seen[0].body.model, "vlm-server-model");
  } finally {
    await srv.close();
  }
});

test("fails over automatically to fallback model when primary returns 503", async () => {
  const calls = [];
  const srv = await startServer({
    primaryBaseUrl: "https://primary.example/v1",
    primaryModel: "vlm-primary",
    primaryApiKey: "sk-primary",
    fallbackBaseUrl: "https://fallback.example/v1",
    fallbackModel: "vlm-fallback",
    fallbackApiKey: "sk-fallback",
    fetchImpl: async (url, init) => {
      calls.push({ url, model: JSON.parse(init.body).model });
      if (url.includes("primary")) {
        return new Response("{\"error\":\"overloaded\"}", { status: 503 });
      }
      return new Response(JSON.stringify({ choices: [{ message: { content: "fallback-ok" } }] }), { status: 200 });
    },
  });
  try {
    const res = await fetch(`${srv.baseUrl}/v1/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ model: "lumi-director", messages: [{ role: "user", content: "hi" }] }),
    });
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("x-lumi-upstream"), "fallback");
    assert.equal(calls.length, 2);
    assert.equal(calls[1].model, "vlm-fallback");
  } finally {
    await srv.close();
  }
});

test("enforces rate limiting and optional client app token", async () => {
  const srv = await startServer({
    primaryBaseUrl: "https://primary.example/v1",
    primaryModel: "vlm-primary",
    primaryApiKey: "sk-primary",
    appToken: "lumi-beta-token",
    rateLimitMax: 2,
    rateLimitWindowMs: 60_000,
    fetchImpl: async () => new Response(JSON.stringify({ choices: [] }), { status: 200 }),
  });
  try {
    const unauth = await fetch(`${srv.baseUrl}/v1/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ messages: [] }),
    });
    assert.equal(unauth.status, 401);

    const headers = {
      "Content-Type": "application/json",
      Authorization: "Bearer lumi-beta-token",
      "X-Lumi-Device": "device-1",
    };
    const r1 = await fetch(`${srv.baseUrl}/v1/chat/completions`, { method: "POST", headers, body: JSON.stringify({ messages: [] }) });
    const r2 = await fetch(`${srv.baseUrl}/v1/chat/completions`, { method: "POST", headers, body: JSON.stringify({ messages: [] }) });
    const r3 = await fetch(`${srv.baseUrl}/v1/chat/completions`, { method: "POST", headers, body: JSON.stringify({ messages: [] }) });
    assert.equal(r1.status, 200);
    assert.equal(r2.status, 200);
    assert.equal(r3.status, 429);
    assert.ok(Number(r3.headers.get("retry-after")) >= 1);
  } finally {
    await srv.close();
  }
});
