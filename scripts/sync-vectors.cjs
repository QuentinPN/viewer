// Generates docs/sync/vectors.json (ADR 0124). Run: node scripts/sync-vectors.cjs .
const fs = require("node:fs");
const path = require("node:path");
const root = process.argv[2];
const env = (kind, extra = {}, base = {}) =>
  JSON.stringify({
    tv: "sync",
    v: 1,
    from: "sess-a1",
    link: "link-0001",
    seq: 7,
    kind,
    ...base,
    ...extra,
  });

const valid = [
  { name: "join", raw: env("join"), kind: "join", fields: {} },
  {
    name: "load full",
    raw: env("load", {
      itemId: "68397",
      mediaSourceId: "mediasource_68397",
      positionMs: 754000,
      audioStreamIndex: 1,
      subtitleStreamIndex: 3,
    }),
    kind: "load",
    fields: {
      itemId: "68397",
      mediaSourceId: "mediasource_68397",
      positionMs: 754000,
      audioStreamIndex: 1,
      subtitleStreamIndex: 3,
    },
  },
  {
    name: "load minimal",
    raw: env("load", { itemId: "68397", positionMs: 0 }),
    kind: "load",
    fields: { itemId: "68397", positionMs: 0 },
  },
  {
    name: "load no subtitles",
    raw: env("load", { itemId: "1", positionMs: 5, subtitleStreamIndex: -1 }),
    kind: "load",
    fields: { itemId: "1", positionMs: 5, subtitleStreamIndex: -1 },
  },
  {
    name: "ready",
    raw: env("ready", { itemId: "68397", positionMs: 754000 }),
    kind: "ready",
    fields: { itemId: "68397", positionMs: 754000 },
  },
  {
    name: "state playing",
    raw: env("state", {
      itemId: "68397",
      positionMs: 755012,
      paused: false,
      buffering: false,
    }),
    kind: "state",
    fields: {
      itemId: "68397",
      positionMs: 755012,
      paused: false,
      buffering: false,
    },
  },
  {
    name: "state paused buffering",
    raw: env("state", {
      itemId: "68397",
      positionMs: 1,
      paused: true,
      buffering: true,
    }),
    kind: "state",
    fields: { itemId: "68397", positionMs: 1, paused: true, buffering: true },
  },
  {
    name: "cmd pause",
    raw: env("cmd", { action: "pause" }),
    kind: "cmd",
    fields: { action: "pause" },
  },
  {
    name: "cmd play",
    raw: env("cmd", { action: "play" }),
    kind: "cmd",
    fields: { action: "play" },
  },
  {
    name: "cmd seek",
    raw: env("cmd", { action: "seek", positionMs: 128000 }),
    kind: "cmd",
    fields: { action: "seek", positionMs: 128000 },
  },
  {
    name: "cmd next",
    raw: env("cmd", { action: "next" }),
    kind: "cmd",
    fields: { action: "next" },
  },
  {
    name: "cmd previous",
    raw: env("cmd", { action: "previous" }),
    kind: "cmd",
    fields: { action: "previous" },
  },
  {
    name: "buffering on",
    raw: env("buffering", { on: true }),
    kind: "buffering",
    fields: { on: true },
  },
  {
    name: "ping with drift",
    raw: env("ping", { t: 123456789, driftMs: -42 }),
    kind: "ping",
    fields: { t: 123456789, driftMs: -42 },
  },
  {
    name: "ping without drift",
    raw: env("ping", { t: 0 }),
    kind: "ping",
    fields: { t: 0 },
  },
  {
    name: "pong",
    raw: env("pong", { t: 123456789 }),
    kind: "pong",
    fields: { t: 123456789 },
  },
  {
    name: "bye stopped",
    raw: env("bye", { reason: "stopped" }),
    kind: "bye",
    fields: { reason: "stopped" },
  },
  {
    name: "bye timeout",
    raw: env("bye", { reason: "timeout" }),
    kind: "bye",
    fields: { reason: "timeout" },
  },
  {
    name: "unknown field ignored",
    raw: env("cmd", { action: "pause", later: { x: 1 } }),
    kind: "cmd",
    fields: { action: "pause" },
  },
  {
    name: "max position",
    raw: env("ready", { itemId: "a", positionMs: 86400000 }),
    kind: "ready",
    fields: { itemId: "a", positionMs: 86400000 },
  },
];
for (const v of valid) {
  v.from = "sess-a1";
  v.link = "link-0001";
  v.seq = 7;
}

const pad = "x".repeat(1100);
const invalid = [
  { name: "not json", raw: "{tv:sync", reason: "json" },
  { name: "json array", raw: "[1,2]", reason: "json" },
  {
    name: "too long",
    raw: env("cmd", { action: "pause", pad }),
    reason: "size",
  },
  {
    name: "not ours",
    raw: JSON.stringify({
      tv: "other",
      v: 1,
      from: "sess-a1",
      link: "link-0001",
      seq: 1,
      kind: "join",
    }),
    reason: "version",
  },
  { name: "other version", raw: env("join", {}, { v: 2 }), reason: "version" },
  {
    name: "missing from",
    raw: JSON.stringify({
      tv: "sync",
      v: 1,
      link: "link-0001",
      seq: 1,
      kind: "join",
    }),
    reason: "field",
  },
  {
    name: "bad from chars",
    raw: env("join", { from: "a b" }),
    reason: "field",
  },
  { name: "short link", raw: env("join", { link: "abc" }), reason: "field" },
  { name: "negative seq", raw: env("join", { seq: -1 }), reason: "field" },
  { name: "fractional seq", raw: env("join", { seq: 1.5 }), reason: "field" },
  { name: "unknown kind", raw: env("dance"), reason: "kind" },
  {
    name: "load without item",
    raw: env("load", { positionMs: 1 }),
    reason: "field",
  },
  {
    name: "load position too big",
    raw: env("load", { itemId: "1", positionMs: 86400001 }),
    reason: "field",
  },
  {
    name: "load negative position",
    raw: env("load", { itemId: "1", positionMs: -5 }),
    reason: "field",
  },
  {
    name: "load bad item chars",
    raw: env("load", { itemId: "../x", positionMs: 1 }),
    reason: "field",
  },
  {
    name: "load audio index too big",
    raw: env("load", { itemId: "1", positionMs: 1, audioStreamIndex: 1000 }),
    reason: "field",
  },
  {
    name: "state paused not boolean",
    raw: env("state", {
      itemId: "1",
      positionMs: 1,
      paused: "no",
      buffering: false,
    }),
    reason: "field",
  },
  {
    name: "cmd unknown action",
    raw: env("cmd", { action: "volume" }),
    reason: "field",
  },
  {
    name: "cmd seek without position",
    raw: env("cmd", { action: "seek" }),
    reason: "field",
  },
  {
    name: "bye unknown reason",
    raw: env("bye", { reason: "bored" }),
    reason: "field",
  },
  { name: "ping without t", raw: env("ping"), reason: "field" },
];

// accept(): link filtering once decoded. state = null when not linked.
const accept = [
  {
    name: "join when idle starts a link",
    state: null,
    msg: { from: "tv-1", link: "link-new1", seq: 0, kind: "join" },
    accept: true,
  },
  {
    name: "load when idle starts a link",
    state: null,
    msg: { from: "pc-1", link: "link-new1", seq: 0, kind: "load" },
    accept: true,
  },
  {
    name: "state when idle is ignored",
    state: null,
    msg: { from: "pc-1", link: "link-new1", seq: 3, kind: "state" },
    accept: false,
  },
  {
    name: "peer, same link, newer seq",
    state: { peer: "pc-1", link: "link-0001", lastSeq: 4 },
    msg: { from: "pc-1", link: "link-0001", seq: 5, kind: "state" },
    accept: true,
  },
  {
    name: "duplicate seq",
    state: { peer: "pc-1", link: "link-0001", lastSeq: 5 },
    msg: { from: "pc-1", link: "link-0001", seq: 5, kind: "state" },
    accept: false,
  },
  {
    name: "older seq",
    state: { peer: "pc-1", link: "link-0001", lastSeq: 9 },
    msg: { from: "pc-1", link: "link-0001", seq: 2, kind: "state" },
    accept: false,
  },
  {
    name: "first message from peer in link",
    state: { peer: "pc-1", link: "link-0001", lastSeq: null },
    msg: { from: "pc-1", link: "link-0001", seq: 0, kind: "load" },
    accept: true,
  },
  {
    name: "other session",
    state: { peer: "pc-1", link: "link-0001", lastSeq: 4 },
    msg: { from: "pc-2", link: "link-0001", seq: 5, kind: "state" },
    accept: false,
  },
  {
    name: "peer, other link",
    state: { peer: "pc-1", link: "link-0001", lastSeq: 4 },
    msg: { from: "pc-1", link: "link-0002", seq: 5, kind: "state" },
    accept: false,
  },
  {
    name: "a new join while linked is still read (to answer busy)",
    state: { peer: "tv-1", link: "link-0001", lastSeq: 4 },
    msg: { from: "tv-2", link: "link-0009", seq: 0, kind: "join" },
    accept: "busy",
  },
  {
    name: "a new load while linked is still read (to answer busy)",
    state: { peer: "pc-1", link: "link-0001", lastSeq: 4 },
    msg: { from: "pc-2", link: "link-0009", seq: 0, kind: "load" },
    accept: "busy",
  },
];

const rtt = [
  { samples: [], rttMs: 0 },
  { samples: [40], rttMs: 40 },
  { samples: [10, 50, 30], rttMs: 30 },
  { samples: [10, 20, 30, 40], rttMs: 30 },
  { samples: [100, 10, 20, 30, 40, 50], rttMs: 30 },
  { samples: [5, 5, 300, 5, 5], rttMs: 5 },
];

const projection = [
  {
    name: "playing",
    state: { positionMs: 10000, paused: false, buffering: false },
    recvAt: 1000,
    now: 1500,
    rttMs: 40,
    offsetMs: 0,
    targetMs: 10520,
  },
  {
    name: "paused does not move",
    state: { positionMs: 10000, paused: true, buffering: false },
    recvAt: 1000,
    now: 1500,
    rttMs: 40,
    offsetMs: 0,
    targetMs: 10000,
  },
  {
    name: "buffering does not move",
    state: { positionMs: 10000, paused: false, buffering: true },
    recvAt: 1000,
    now: 1500,
    rttMs: 40,
    offsetMs: 0,
    targetMs: 10000,
  },
  {
    name: "tv offset",
    state: { positionMs: 10000, paused: false, buffering: false },
    recvAt: 1000,
    now: 1500,
    rttMs: 40,
    offsetMs: 300,
    targetMs: 10220,
  },
  {
    name: "odd rtt halves down",
    state: { positionMs: 0, paused: false, buffering: false },
    recvAt: 0,
    now: 0,
    rttMs: 41,
    offsetMs: 0,
    targetMs: 20,
  },
  {
    name: "offset never goes below zero",
    state: { positionMs: 100, paused: true, buffering: false },
    recvAt: 0,
    now: 0,
    rttMs: 0,
    offsetMs: 500,
    targetMs: 0,
  },
];

// decide(): e = tvPositionMs - targetMs. Output keys: pause, resume (true), speed (number), seekTo (ms). {} = nothing.
const d = (name, input, out) => ({
  name,
  input: {
    leaderPaused: false,
    tvPaused: false,
    correcting: false,
    sinceSeekMs: 10000,
    targetMs: 100000,
    ...input,
  },
  out,
});
const decide = [
  d("seek settling", { tvPositionMs: 105000, sinceSeekMs: 500 }, {}),
  d(
    "leader paused, tv playing, close",
    { leaderPaused: true, tvPositionMs: 100010 },
    { pause: true },
  ),
  d(
    "leader paused, tv paused, off by 200",
    { leaderPaused: true, tvPaused: true, tvPositionMs: 100200 },
    { seekTo: 100000 },
  ),
  d(
    "leader paused, tv paused, exactly 60 is fine",
    { leaderPaused: true, tvPaused: true, tvPositionMs: 100060 },
    {},
  ),
  d(
    "leader paused while correcting",
    {
      leaderPaused: true,
      tvPaused: true,
      correcting: true,
      tvPositionMs: 100000,
    },
    { speed: 1 },
  ),
  d(
    "leader paused, tv playing, far",
    { leaderPaused: true, correcting: true, tvPositionMs: 98000 },
    { pause: true, speed: 1, seekTo: 100000 },
  ),
  d(
    "leader plays, tv paused, aligned",
    { tvPaused: true, tvPositionMs: 100000 },
    { resume: true },
  ),
  d(
    "leader plays, tv paused, far",
    { tvPaused: true, tvPositionMs: 101200 },
    { resume: true, seekTo: 100000 },
  ),
  d("far ahead", { tvPositionMs: 101500 }, { seekTo: 100000 }),
  d(
    "far behind while correcting",
    { correcting: true, tvPositionMs: 98500 },
    { seekTo: 100000, speed: 1 },
  ),
  d("1000 is not far", { tvPositionMs: 101000 }, { speed: 0.96 }),
  d("small ahead", { tvPositionMs: 100030 }, {}),
  d("60 starts correcting", { tvPositionMs: 100060 }, { speed: 0.96 }),
  d("ahead 80", { tvPositionMs: 100080 }, { speed: 0.96 }),
  d("behind 80", { tvPositionMs: 99920 }, { speed: 1.04 }),
  d(
    "correcting keeps going above 20",
    { correcting: true, tvPositionMs: 100040 },
    { speed: 0.96 },
  ),
  d(
    "correcting behind keeps going",
    { correcting: true, tvPositionMs: 99979 },
    { speed: 1.04 },
  ),
  d(
    "correcting stops at 20",
    { correcting: true, tvPositionMs: 99980 },
    { speed: 1 },
  ),
  d(
    "correcting stops near zero",
    { correcting: true, tvPositionMs: 100015 },
    { speed: 1 },
  ),
];

const out = {
  comment:
    "Shared test vectors for ADR 0124. Desktop (Rust) and Android TV (Kotlin) tests read this file.",
  maxBytes: 1024,
  valid,
  invalid,
  accept,
  rtt,
  projection,
  decide,
};
fs.mkdirSync(path.join(root, "docs", "sync"), { recursive: true });
fs.writeFileSync(
  path.join(root, "docs", "sync", "vectors.json"),
  `${JSON.stringify(out, null, 2)}\n`,
);
console.log(
  "ok",
  valid.length,
  invalid.length,
  accept.length,
  rtt.length,
  projection.length,
  decide.length,
);
