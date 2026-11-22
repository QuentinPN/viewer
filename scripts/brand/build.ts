// node scripts/brand/build.ts: generates every logo file of the app from the
// art in scripts/brand/raton.ts. The outputs are committed; run it again (and
// commit the result) whenever the art changes. It needs Chrome to rasterize
// the SVGs (headless; path in $CHROME, default C:/Program Files/Google/Chrome)
// and no npm dependency: the .ico files are written here.
//
//   src/lib/brand/<id>.svg                      desktop UI (Logo.svelte shows default.svg)
//   go-app/internal/brand/icons/<id>.png        256 px window icons
//   go-app/build/windows/icon.ico, go-app/build/logo.svg   default logo, .ico of 16..256 px;
//                                               the NSIS installer takes it as its icon
//   android-tv/app/src/main/res/drawable-nodpi/icon.png (432), banner.png (640x360): default;
//   ... icon_<id>.png / banner_<id>.png for the other seasons (<id> with _ for -),
//   ... and android-tv/app/src/debug/res/drawable-nodpi/icon.png, banner.png (hard hat; banner + orange band)
//
//   go-app/cmd/viewer/rsrc_windows_amd64.syso   the exe's icon resource, made from icon.ico
//                                               (`go build` links any .syso of the package by itself)
//
// The .syso needs Go on PATH and the network once (go-winres, pinned, cached by Go).
//
// <id> is a season: default, epiphany, winter, valentine, april-fools, easter,
// music, summer, bastille, autumn, halloween, christmas, new-year. The hard hat
// raccoon is a logo id of its own, "debug" (not a season): debug builds only,
// src/lib/brand/debug.svg and go-app/internal/brand/icons/debug.png.

import { spawnSync } from "node:child_process";
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { inflateSync } from "node:zlib";
import { fail, root } from "../step.ts";
import { DEBUG, logoSvg, SEASONS, type Season } from "./raton.ts";

const chrome =
  process.env.CHROME ?? "C:/Program Files/Google/Chrome/Application/chrome.exe";
const work = mkdtempSync(join(tmpdir(), "viewer-brand-"));

const ICO_SIZES = [16, 24, 32, 48, 64, 128, 256];
const DEBUG_BAND = { color: "#F28C28", height: 14 / 180 }; // android-tv/app/src/debug: 14 of 180 dp

// --- PNG: just enough to check what Chrome drew ----------------------------

interface Png {
  width: number;
  height: number;
  /** Alpha of the pixel (x, y). */
  alpha(x: number, y: number): number;
}

function readPng(buf: Buffer): Png {
  if (buf.readUInt32BE(0) !== 0x89504e47) fail("capture : pas un PNG");
  const width = buf.readUInt32BE(16);
  const height = buf.readUInt32BE(20);
  const colorType = buf[25];
  if (buf[24] !== 8 || (colorType !== 6 && colorType !== 2))
    fail(`capture : PNG ${buf[24]} bits / type ${colorType} non géré`);
  const bpp = colorType === 6 ? 4 : 3;
  const idat: Buffer[] = [];
  for (let at = 8; at < buf.length; ) {
    const len = buf.readUInt32BE(at);
    if (buf.toString("latin1", at + 4, at + 8) === "IDAT")
      idat.push(buf.subarray(at + 8, at + 8 + len));
    at += 12 + len;
  }
  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * bpp;
  const px = Buffer.alloc(height * stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    for (let i = 0; i < stride; i++) {
      const x = raw[y * (stride + 1) + 1 + i] ?? 0;
      const a = i >= bpp ? (px[y * stride + i - bpp] ?? 0) : 0;
      const b = y > 0 ? (px[(y - 1) * stride + i] ?? 0) : 0;
      const c = y > 0 && i >= bpp ? (px[(y - 1) * stride + i - bpp] ?? 0) : 0;
      let v = x;
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c;
        const [pa, pb, pc] = [
          Math.abs(p - a),
          Math.abs(p - b),
          Math.abs(p - c),
        ];
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      px[y * stride + i] = v & 255;
    }
  }
  return {
    width,
    height,
    alpha: (x, y) => (bpp === 4 ? (px[y * stride + x * 4 + 3] ?? 0) : 255),
  };
}

// --- Rasterization ---------------------------------------------------------

let shots = 0;

/** Draws `svg` (a full <svg> element) at width x height with headless Chrome. */
function rasterize(svg: string, width: number, height: number): Buffer {
  const n = shots++;
  const html = join(work, `${n}.html`);
  const png = join(work, `${n}.png`);
  writeFileSync(
    html,
    `<!doctype html><meta charset="utf-8"><style>html,body{margin:0;overflow:hidden;background:transparent}` +
      `svg{display:block;width:${width}px;height:${height}px}</style>${svg}`,
  );
  const r = spawnSync(
    chrome,
    [
      "--headless",
      "--disable-gpu",
      "--no-first-run",
      "--force-device-scale-factor=1",
      `--user-data-dir=${join(work, `profile${n}`)}`,
      `--screenshot=${png}`,
      `--window-size=${width},${height}`,
      "--default-background-color=00000000",
      "--hide-scrollbars",
      `file:///${html.replaceAll("\\", "/")}`,
    ],
    { stdio: "pipe" },
  );
  if (r.status !== 0) fail(`Chrome : code ${r.status}\n${r.stderr}`);
  const buf = readFileSync(png);
  const img = readPng(buf);
  if (img.width !== width || img.height !== height)
    fail(`capture ${img.width}x${img.height} au lieu de ${width}x${height}`);
  return buf;
}

/** A tile must be (nearly) transparent at its rounded corners, which antialiasing
 *  barely touches even at 16 px, and opaque in the middle; a banner opaque everywhere. */
function checkAlpha(buf: Buffer, label: string, tile: boolean) {
  const img = readPng(buf);
  const { width: w, height: h } = img;
  const corners = [
    [0, 0],
    [w - 1, 0],
    [0, h - 1],
    [w - 1, h - 1],
  ] as const;
  const centre = img.alpha(w >> 1, h >> 1);
  if (centre !== 255) fail(`${label} : centre non opaque (alpha ${centre})`);
  for (const [x, y] of corners) {
    const a = img.alpha(x, y);
    if (tile ? a > 16 : a !== 255)
      fail(`${label} : coin (${x}, ${y}) alpha ${a}`);
  }
}

// --- Files -----------------------------------------------------------------

function put(path: string, data: string | Buffer) {
  const file = join(root, path);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, data);
}

/** An .ico holding one PNG per size (the format Windows reads since Vista). */
function ico(images: { size: number; png: Buffer }[]): Buffer {
  const head = Buffer.alloc(6 + 16 * images.length);
  head.writeUInt16LE(1, 2); // type: icon
  head.writeUInt16LE(images.length, 4);
  let offset = head.length;
  images.forEach(({ size, png }, i) => {
    const at = 6 + 16 * i;
    head[at] = size === 256 ? 0 : size; // width, 0 means 256
    head[at + 1] = size === 256 ? 0 : size; // height
    head.writeUInt16LE(1, at + 4); // planes
    head.writeUInt16LE(32, at + 6); // bits per pixel
    head.writeUInt32LE(png.length, at + 8);
    head.writeUInt32LE(offset, at + 12);
    offset += png.length;
  });
  return Buffer.concat([head, ...images.map((i) => i.png)]);
}

/** Android TV banner (640x360 = 320x180 dp): the season's content as tall as the banner, centred, on a flat tile colour. */
function bannerSvg({ bg, content }: Season, band = false): string {
  return (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 360">' +
    `<rect width="640" height="360" fill="${bg}"/>` +
    // Nested svg: keeps the 512 clipping of the tile (the winter scarf runs past it).
    `<svg x="140" width="360" height="360" viewBox="0 0 512 512">${content}</svg>` +
    (band
      ? `<rect y="${360 * (1 - DEBUG_BAND.height)}" width="640" height="${360 * DEBUG_BAND.height}" fill="${DEBUG_BAND.color}"/>`
      : "") +
    "</svg>"
  );
}

const androidRes = "android-tv/app/src/main/res/drawable-nodpi";

try {
  for (const season of SEASONS) {
    const { id } = season;
    const resName = id.replaceAll("-", "_"); // Android resource names: a-z, 0-9, _
    const svg = logoSvg(season);
    put(`src/lib/brand/${id}.svg`, svg);

    const icon = (size: number) => {
      const png = rasterize(svg, size, size);
      checkAlpha(png, `${id} ${size}px`, true);
      return png;
    };
    put(`go-app/internal/brand/icons/${id}.png`, icon(256));
    const androidIcon = icon(432);
    const banner = rasterize(bannerSvg(season), 640, 360);
    checkAlpha(banner, `${id} banner`, false);
    if (id !== "default") {
      put(`${androidRes}/icon_${resName}.png`, androidIcon);
      put(`${androidRes}/banner_${resName}.png`, banner);
      continue;
    }
    // The default logo is also what the app and the installer use.
    put("go-app/build/logo.svg", svg);
    put(
      "go-app/build/windows/icon.ico",
      ico(ICO_SIZES.map((size) => ({ size, png: icon(size) }))),
    );
    put(`${androidRes}/icon.png`, androidIcon);
    put(`${androidRes}/banner.png`, banner);
    // Debug build: the raccoon in a hard hat, and the banner keeps its orange band.
    const debugRes = "android-tv/app/src/debug/res/drawable-nodpi";
    const debugBanner = rasterize(bannerSvg(DEBUG, true), 640, 360);
    checkAlpha(debugBanner, "debug banner", false);
    put(`${debugRes}/banner.png`, debugBanner);
    const debugIcon = rasterize(logoSvg(DEBUG), 432, 432);
    checkAlpha(debugIcon, "debug icon", true);
    put(`${debugRes}/icon.png`, debugIcon);
    // Desktop debug build: the same hard hat, as UI logo and window icon.
    put("src/lib/brand/debug.svg", logoSvg(DEBUG));
    const debugWindowIcon = rasterize(logoSvg(DEBUG), 256, 256);
    checkAlpha(debugWindowIcon, "debug 256px", true);
    put("go-app/internal/brand/icons/debug.png", debugWindowIcon);
  }
  // Icon resource of the Windows exe. go-winres: pinned, icon only (no manifest: the
  // exe has none today, and one could change the DPI behaviour of the video window;
  // no version info either, it would go stale at each release).
  const winres = spawnSync(
    "go",
    [
      "run",
      "github.com/tc-hib/go-winres@v0.3.3",
      "simply",
      "--icon",
      "build/windows/icon.ico",
      "--arch",
      "amd64",
      "--manifest",
      "none",
      "--out",
      "cmd/viewer/rsrc",
    ],
    { cwd: join(root, "go-app"), stdio: "inherit" },
  );
  if (winres.status !== 0) fail(`go-winres : code ${winres.status}`);
  console.log(`${SEASONS.length} logos, ${shots} captures`);
} finally {
  rmSync(work, { recursive: true, force: true });
}
