// Local network proxy for the connection scenarios (ADR 0012): the app signs in
// through http://127.0.0.1:<port>, and the scenario simulates server outages here
// instead of touching the server. Modes:
// - "up": every connection is forwarded to the target;
// - "down": the port is closed (connection refused) and open sockets are reset,
//   like a server that is switched off or a cable pulled out;
// - "blackhole": connections are accepted but nothing is ever answered, like a
//   server that hangs (the app's own timeouts must fire);
// - "reject": authenticated requests get 401, like a token revoked on the server.
//   The public probe still goes through. Nothing reaches the server in that mode;
// - "junk-direct": direct file requests get bytes no player can read, like a file
//   mpv cannot open (ADR 0023). The rest is forwarded, transcodes included;
// - "junk-all": HLS playlists too, so that a transcode cannot open either.
import { randomBytes } from "node:crypto";
import net from "node:net";

const DIRECT_FILE = /^GET \S*\/videos\/\S+\/original\.\S* HTTP/i;
const HLS_PLAYLIST = /^GET \S*\/videos\/\S+\.m3u8\S* HTTP/i;
const JUNK = randomBytes(256 * 1024);
const JUNK_RESPONSE = Buffer.concat([
  Buffer.from(
    `HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${JUNK.length}\r\nConnection: close\r\n\r\n`,
  ),
  JUNK,
]);

export async function startProxy(targetUrl, port = 0) {
  const target = new URL(targetUrl);
  const targetPort = Number(
    target.port || (target.protocol === "https:" ? 443 : 80),
  );
  if (target.protocol !== "http:")
    throw new Error("the proxy only forwards plain HTTP (local server)");
  let mode = "up";
  const sockets = new Set();
  const counts = { forwarded: 0, refused: 0, held: 0, rejected: 0, junk: 0 };

  const server = net.createServer((client) => {
    sockets.add(client);
    client.on("close", () => sockets.delete(client));
    client.on("error", () => {});
    if (mode === "blackhole") {
      counts.held += 1;
      client.on("data", () => {});
      return;
    }
    if (mode === "reject") {
      rejectAuthenticated(client);
      return;
    }
    if (mode === "junk-direct" || mode === "junk-all") {
      serveJunk(client, mode === "junk-all");
      return;
    }
    counts.forwarded += 1;
    const upstream = net.connect(targetPort, target.hostname);
    sockets.add(upstream);
    upstream.on("close", () => sockets.delete(upstream));
    upstream.on("error", () => client.destroy());
    client.on("error", () => upstream.destroy());
    client.pipe(upstream);
    upstream.pipe(client);
  });

  /**
   * 401 when a request carries a token; the rest is forwarded. Every chunk is
   * inspected: a keep-alive connection opened by the public probe must not
   * carry an authenticated request through.
   */
  function rejectAuthenticated(client) {
    let upstream = null;
    client.on("data", (chunk) => {
      const text = chunk.toString("latin1");
      if (
        /^x-emby-token:/im.test(text) ||
        /^x-emby-authorization:.*Token=/im.test(text)
      ) {
        counts.rejected += 1;
        upstream?.destroy();
        client.end(
          "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        );
        return;
      }
      if (!upstream) {
        counts.forwarded += 1;
        upstream = net.connect(targetPort, target.hostname);
        sockets.add(upstream);
        upstream.on("close", () => sockets.delete(upstream));
        upstream.on("error", () => client.destroy());
        upstream.pipe(client);
      }
      upstream.write(chunk);
    });
  }

  /** Same inspection as `rejectAuthenticated`, on the request line. */
  function serveJunk(client, playlistsToo) {
    let upstream = null;
    client.on("data", (chunk) => {
      const line = chunk.toString("latin1").split("\r\n", 1)[0];
      if (DIRECT_FILE.test(line) || (playlistsToo && HLS_PLAYLIST.test(line))) {
        counts.junk += 1;
        upstream?.destroy();
        client.end(JUNK_RESPONSE);
        return;
      }
      if (!upstream) {
        counts.forwarded += 1;
        upstream = net.connect(targetPort, target.hostname);
        sockets.add(upstream);
        upstream.on("close", () => sockets.delete(upstream));
        upstream.on("error", () => client.destroy());
        upstream.pipe(client);
      }
      upstream.write(chunk);
    });
  }

  const listen = (p) =>
    new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(p, "127.0.0.1", () => {
        server.off("error", reject);
        resolve(server.address().port);
      });
    });
  const actualPort = await listen(port);

  return {
    url: `http://127.0.0.1:${actualPort}`,
    counts,
    get mode() {
      return mode;
    },
    async set(next) {
      if (next === mode) return;
      const wasDown = mode === "down";
      mode = next;
      // Any switch drops open connections: keep-alive sockets must not carry
      // the previous mode over (e.g. the player's stream after "down").
      for (const s of sockets) s.destroy();
      sockets.clear();
      if (next === "down") {
        await new Promise((r) => server.close(() => r()));
      } else if (wasDown) {
        await listen(actualPort);
      }
    },
    async stop() {
      for (const s of sockets) s.destroy();
      if (server.listening) await new Promise((r) => server.close(() => r()));
    },
  };
}
