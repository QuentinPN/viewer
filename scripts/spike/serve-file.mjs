// Minimal local HTTP server for Gate 1 HTTP-stream tests: serves ONE file at
// /media with Range support, bound to 127.0.0.1 only.
// Optional: REQUIRE_TOKEN=<value> rejects requests without `X-Emby-Token: <value>`,
// to verify that the player sends auth as a header rather than in the URL.
// Usage: node scripts/spike/serve-file.mjs <file> [port]
import { createReadStream, statSync } from "node:fs";
import { createServer } from "node:http";

const [file, port = "8099"] = process.argv.slice(2);
if (!file) {
  console.error("usage: serve-file.mjs <file> [port]");
  process.exit(2);
}
const size = statSync(file).size;
const token = process.env.REQUIRE_TOKEN;

createServer((req, res) => {
  const tokenOk = !token || req.headers["x-emby-token"] === token;
  const urlHasToken = /api_key=|token=/i.test(req.url ?? "");
  console.log(
    `${req.method} ${req.url} range=${req.headers.range ?? "-"} headerToken=${tokenOk} tokenInUrl=${urlHasToken}`,
  );
  if (req.url !== "/media") return void res.writeHead(404).end();
  if (!tokenOk) return void res.writeHead(401).end();
  const m = /bytes=(\d*)-(\d*)/.exec(req.headers.range ?? "");
  if (!m) {
    res.writeHead(200, { "Content-Length": size, "Accept-Ranges": "bytes" });
    return void createReadStream(file).pipe(res);
  }
  const start = m[1] ? Number(m[1]) : size - Number(m[2]);
  const end = m[1] && m[2] ? Math.min(Number(m[2]), size - 1) : size - 1;
  res.writeHead(206, {
    "Content-Range": `bytes ${start}-${end}/${size}`,
    "Content-Length": end - start + 1,
    "Accept-Ranges": "bytes",
  });
  createReadStream(file, { start, end }).pipe(res);
}).listen(Number(port), "127.0.0.1", () =>
  console.log(`listening 127.0.0.1:${port}`),
);
