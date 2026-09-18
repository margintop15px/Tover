// Local test gateway: real GoTrue + PostgREST, with no remote fallback.
import { createServer, request as upstreamRequest } from "node:http";

createServer((request, response) => {
  const url = new URL(request.url!, "http://127.0.0.1:3421");
  response.setHeader("Access-Control-Allow-Origin", "http://127.0.0.1:3420");
  response.setHeader("Access-Control-Allow-Headers", "*");
  response.setHeader("Access-Control-Allow-Methods", "GET,POST,PATCH,PUT,DELETE,OPTIONS");
  if (request.method === "OPTIONS") { response.end(); return; }
  if (url.pathname === "/health") { response.end("ok"); return; }
  const auth = url.pathname.startsWith("/auth/v1/");
  if (!auth && !url.pathname.startsWith("/rest/v1/")) { response.writeHead(404).end(); return; }
  const headers = { ...request.headers, host: `127.0.0.1:${auth ? 3422 : 3423}` };
  const upstream = upstreamRequest({ hostname: "127.0.0.1", port: auth ? 3422 : 3423,
    method: request.method, path: url.pathname.replace(auth ? "/auth/v1" : "/rest/v1", "") + url.search, headers,
  }, (incoming) => {
    const forwarded = { ...incoming.headers };
    delete forwarded["access-control-allow-origin"];
    response.writeHead(incoming.statusCode || 502, forwarded);
    incoming.pipe(response);
  });
  upstream.on("error", () => { response.writeHead(502).end(); });
  request.pipe(upstream);
}).listen(3421, "127.0.0.1");
