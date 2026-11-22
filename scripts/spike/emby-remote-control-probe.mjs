// Step 0 probe: does Emby relay remote-control commands between two sessions of the same user?
// Usage: node scripts/spike/emby-remote-control-probe.mjs <path to debug.env> <result.json>   (MEDIA_CONTROL=0 to declare SupportsMediaControl false)
// Secrets come from debug.env and are never printed (tokens are redacted from every log line).
import { readFileSync, writeFileSync } from "node:fs";

const env = Object.fromEntries(
  readFileSync(process.argv[2], "utf8")
    .split(/\r?\n/)
    .filter((l) => l.includes("="))
    .map((l) => [
      l.slice(0, l.indexOf("=")).trim(),
      l.slice(l.indexOf("=") + 1).trim(),
    ]),
);
const outFile = process.argv[3];
let host = env.EMBY_IP.replace(/\/+$/, "");
if (!/^https?:\/\//.test(host)) host = `http://${host}`;
if (!/:\d+$/.test(host)) host += ":8096";
const secrets = [env.PASSWORD];
const redact = (s) =>
  secrets.reduce(
    (acc, x) => (x ? acc.split(x).join("<redacted>") : acc),
    String(s),
  );
const log = [];
const note = (...a) => {
  const line = redact(
    a.map((x) => (typeof x === "string" ? x : JSON.stringify(x))).join(" "),
  );
  log.push(line);
  console.log(line);
};
const now = () => performance.now();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const SUPPORTED = [
  "DisplayMessage",
  "SendString",
  "SetVolume",
  "Mute",
  "Unmute",
  "SetAudioStreamIndex",
  "SetSubtitleStreamIndex",
  "PlayState",
  "Play",
  "PlayNext",
];

function makeClient(role) {
  const device = {
    name: `Viewer probe ${role}`,
    id: `viewer-sync-probe-${role}`,
  };
  const c = {
    role,
    device,
    token: null,
    userId: null,
    sessionId: null,
    ws: null,
    inbox: [],
  };
  c.auth = () =>
    `Emby Client="Viewer", Device="${device.name}", DeviceId="${device.id}", Version="0.0.0-probe"` +
    (c.token ? `, Token="${c.token}"` : "");
  c.req = async (method, path, body) => {
    const t0 = now();
    const res = await fetch(`${host}/emby${path}`, {
      method,
      headers: {
        "X-Emby-Authorization": c.auth(),
        Accept: "application/json",
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {}
    return {
      status: res.status,
      ms: +(now() - t0).toFixed(1),
      json,
      text: text.slice(0, 300),
      t0,
    };
  };
  return c;
}

async function openSocket(c) {
  const url = `${host.replace(/^http/, "ws")}/embywebsocket?api_key=${encodeURIComponent(c.token)}&deviceId=${encodeURIComponent(c.device.id)}`;
  await new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.onopen = () => {
      c.ws = ws;
      resolve();
    };
    ws.onerror = (e) =>
      reject(new Error(`socket ${c.role}: ${e.message ?? "error"}`));
    ws.onmessage = (ev) => {
      const at = now();
      let msg = null;
      try {
        msg = JSON.parse(ev.data);
      } catch {}
      c.inbox.push({ at, msg, raw: String(ev.data).slice(0, 400) });
    };
  });
}

// Waits for a message on `c` received after `since` that matches `pred`.
async function waitFor(c, since, pred, timeoutMs = 3000) {
  const end = now() + timeoutMs;
  while (now() < end) {
    const hit = c.inbox.find((m) => m.at >= since && m.msg && pred(m.msg));
    if (hit) return hit;
    await sleep(2);
  }
  return null;
}

const stats = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  const q = (p) => s[Math.min(s.length - 1, Math.floor(p * s.length))];
  return {
    n: s.length,
    min: +s[0].toFixed(1),
    median: +q(0.5).toFixed(1),
    p95: +q(0.95).toFixed(1),
    max: +s[s.length - 1].toFixed(1),
  };
};

const results = { host: host.replace(/\/\/.*@/, "//"), steps: {} };
const leader = makeClient("desktop");
const follower = makeClient("tv");

try {
  const info = await leader.req("GET", "/System/Info/Public");
  results.server = {
    status: info.status,
    version: info.json?.Version,
    product: info.json?.ProductName,
  };
  note("server", results.server);

  for (const c of [leader, follower]) {
    const r = await c.req("POST", "/Users/AuthenticateByName", {
      Username: env.LOGIN,
      Pw: env.PASSWORD,
    });
    if (r.status !== 200) throw new Error(`auth ${c.role}: HTTP ${r.status}`);
    c.token = r.json.AccessToken;
    c.userId = r.json.User?.Id;
    c.sessionIdFromAuth = r.json.SessionInfo?.Id;
    secrets.push(c.token);
    note(`auth ${c.role}`, {
      status: r.status,
      ms: r.ms,
      hasSessionInfo: !!r.json.SessionInfo,
    });
  }

  for (const c of [leader, follower]) {
    const r = await c.req("POST", "/Sessions/Capabilities/Full", {
      PlayableMediaTypes: ["Video", "Audio"],
      SupportedCommands: SUPPORTED,
      SupportsMediaControl: process.env.MEDIA_CONTROL !== "0",
    });
    results.steps[`capabilities_${c.role}`] = {
      status: r.status,
      ms: r.ms,
      body: r.text,
    };
    note(`capabilities ${c.role}`, results.steps[`capabilities_${c.role}`]);
  }

  for (const c of [leader, follower]) await openSocket(c);
  note("sockets open");
  await sleep(800);

  const sessions = await leader.req(
    "GET",
    `/Sessions?ControllableByUserId=${leader.userId}`,
  );
  const all = sessions.json ?? [];
  const mine = all.filter((s) => s.DeviceId?.startsWith("viewer-sync-probe-"));
  results.steps.sessions = {
    status: sessions.status,
    total: all.length,
    probe: mine.map((s) => ({
      DeviceId: s.DeviceId,
      Client: s.Client,
      DeviceName: s.DeviceName,
      SupportsRemoteControl: s.SupportsRemoteControl,
      SupportedCommands: s.SupportedCommands,
      PlayableMediaTypes: s.PlayableMediaTypes,
      IsActive: s.IsActive,
      keys: Object.keys(s),
    })),
    otherClients: all
      .filter((s) => !s.DeviceId?.startsWith("viewer-sync-probe-"))
      .map((s) => s.Client),
  };
  note("sessions", results.steps.sessions);
  for (const c of [leader, follower]) {
    c.sessionId = mine.find((s) => s.DeviceId === c.device.id)?.Id;
    if (!c.sessionId) throw new Error(`no session for ${c.role}`);
  }
  results.sessionIdMatchesAuth = leader.sessionId === leader.sessionIdFromAuth;

  // One relay attempt: send from `from` to `to`, wait for a socket message matching `pred` on `to`.
  async function relay(label, from, to, method, path, body, pred) {
    const r = await from.req(method, path.replace("{id}", to.sessionId), body);
    const hit = await waitFor(to, r.t0, pred, 3000);
    const out = {
      http: r.status,
      httpMs: r.ms,
      httpBody: r.text || undefined,
      received: !!hit,
      relayMs: hit ? +(hit.at - r.t0).toFixed(1) : null,
      message: hit ? JSON.parse(redact(JSON.stringify(hit.msg))) : null,
    };
    results.steps[label] = out;
    note(label, out);
    return out;
  }
  const isType = (t) => (m) => m.MessageType === t;
  const playstate = (cmd) => (m) =>
    m.MessageType === "Playstate" && m.Data?.Command === cmd;
  const general = (name) => (m) =>
    m.MessageType === "GeneralCommand" && m.Data?.Name === name;

  await relay(
    "playing_pause",
    leader,
    follower,
    "POST",
    "/Sessions/{id}/Playing/Pause",
    undefined,
    playstate("Pause"),
  );
  await relay(
    "playing_unpause",
    leader,
    follower,
    "POST",
    "/Sessions/{id}/Playing/Unpause",
    undefined,
    playstate("Unpause"),
  );
  await relay(
    "playing_playpause",
    leader,
    follower,
    "POST",
    "/Sessions/{id}/Playing/PlayPause",
    undefined,
    playstate("PlayPause"),
  );
  await relay(
    "playing_seek",
    leader,
    follower,
    "POST",
    "/Sessions/{id}/Playing/Seek?SeekPositionTicks=7540000000",
    undefined,
    playstate("Seek"),
  );
  await relay(
    "playing_next",
    leader,
    follower,
    "POST",
    "/Sessions/{id}/Playing/NextTrack",
    undefined,
    playstate("NextTrack"),
  );
  await relay(
    "playing_stop",
    leader,
    follower,
    "POST",
    "/Sessions/{id}/Playing/Stop",
    undefined,
    playstate("Stop"),
  );
  await relay(
    "reverse_pause_tv_to_desktop",
    follower,
    leader,
    "POST",
    "/Sessions/{id}/Playing/Pause",
    undefined,
    playstate("Pause"),
  );

  const payload = JSON.stringify({
    v: 1,
    kind: "state",
    state: "playing",
    positionMs: 754000,
    sentAt: Date.now(),
  });
  await relay(
    "command_sendstring",
    leader,
    follower,
    "POST",
    "/Sessions/{id}/Command",
    { Name: "SendString", Arguments: { String: payload } },
    general("SendString"),
  );
  await relay(
    "command_sendstring_path",
    leader,
    follower,
    "POST",
    "/Sessions/{id}/Command/SendString",
    { Arguments: { String: payload } },
    general("SendString"),
  );
  await relay(
    "command_displaymessage",
    leader,
    follower,
    "POST",
    "/Sessions/{id}/Command",
    {
      Name: "DisplayMessage",
      Arguments: { Header: "Sonde", Text: "test", TimeoutMs: "1000" },
    },
    general("DisplayMessage"),
  );
  await relay(
    "command_unsupported_name",
    leader,
    follower,
    "POST",
    "/Sessions/{id}/Command",
    { Name: "ToggleFullscreen" },
    general("ToggleFullscreen"),
  );
  await relay(
    "message_legacy",
    leader,
    follower,
    "POST",
    "/Sessions/{id}/Message",
    { Header: "Sonde", Text: "test", TimeoutMs: 1000 },
    (m) => m.MessageType === "GeneralCommand" || isType("DisplayMessage")(m),
  );

  // Latency series on the candidate state channel.
  const big = JSON.stringify({ v: 1, kind: "state", pad: "x".repeat(900) });
  for (const [label, str] of [
    ["latency_sendstring", payload],
    ["latency_sendstring_1kb", big],
  ]) {
    const samples = [];
    const http = [];
    let lost = 0;
    for (let i = 0; i < 30; i++) {
      const body = { Name: "SendString", Arguments: { String: `${i}|${str}` } };
      const r = await leader.req(
        "POST",
        `/Sessions/${follower.sessionId}/Command`,
        body,
      );
      const hit = await waitFor(
        follower,
        r.t0,
        (m) =>
          general("SendString")(m) &&
          String(m.Data?.Arguments?.String).startsWith(`${i}|`),
        2000,
      );
      http.push(r.ms);
      if (hit) samples.push(hit.at - r.t0);
      else lost++;
      await sleep(100);
    }
    results.steps[label] = {
      relay: samples.length ? stats(samples) : null,
      http: stats(http),
      lost,
      payloadBytes: str.length,
    };
    note(label, results.steps[label]);
  }
  const pauseSamples = [];
  for (let i = 0; i < 15; i++) {
    const cmd = i % 2 ? "Unpause" : "Pause";
    const r = await leader.req(
      "POST",
      `/Sessions/${follower.sessionId}/Playing/${cmd}`,
    );
    const hit = await waitFor(follower, r.t0, playstate(cmd), 2000);
    if (hit) pauseSamples.push(hit.at - r.t0);
    await sleep(100);
  }
  results.steps.latency_playstate = pauseSamples.length
    ? stats(pauseSamples)
    : null;
  note("latency_playstate", results.steps.latency_playstate);

  // Link loss: close the TV socket, then look at the session list and a send to it.
  follower.ws.close();
  await sleep(1500);
  const after = await leader.req(
    "GET",
    `/Sessions?ControllableByUserId=${leader.userId}`,
  );
  const tvAfter = (after.json ?? []).find(
    (s) => s.DeviceId === follower.device.id,
  );
  results.steps.after_tv_socket_closed = {
    tvListed: !!tvAfter,
    SupportsRemoteControl: tvAfter?.SupportsRemoteControl,
  };
  const sendGone = await leader.req(
    "POST",
    `/Sessions/${follower.sessionId}/Command`,
    { Name: "SendString", Arguments: { String: "x" } },
  );
  results.steps.after_tv_socket_closed.sendStatus = sendGone.status;
  results.steps.after_tv_socket_closed.sendBody = sendGone.text;
  note("after_tv_socket_closed", results.steps.after_tv_socket_closed);
  follower.ws = null;
  // Server-side keep-alive behaviour and any unsolicited traffic.
  results.unsolicited = [leader, follower].map((c) => ({
    role: c.role,
    types: [...new Set(c.inbox.map((m) => m.msg?.MessageType))],
  }));
  note("socket message types", results.unsolicited);
} catch (e) {
  results.error = redact(e.message);
  note("ERROR", results.error);
} finally {
  for (const c of [leader, follower]) {
    try {
      c.ws?.close();
    } catch {}
    if (c.token) {
      const r = await c.req("POST", "/Sessions/Logout");
      note(`logout ${c.role}`, r.status);
    }
  }
  writeFileSync(outFile, redact(JSON.stringify(results, null, 2)));
}
