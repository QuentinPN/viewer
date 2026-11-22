// The Viewer logo: a raccoon whose right eye is the play button, plus the
// seasonal variants (an accessory on the same raccoon). Pure functions that
// return SVG strings in a 512 viewBox; scripts/brand/build.ts writes the files.
// Ported from the owner's Python generators (gen3-gen6, "L · Pelage chaud"),
// number formatting included: `f0` rounds half to even like Python's `:.0f`.

export const NAVY = "#141A3A";
const AMBER = "#FFB547";
const CREAM = "#F3E9D2";
const WARM = "#B9AEA3"; // the raccoon's fur
const RED = "#D8433C";
const PINK = "#E85D9C";
const BLUE = "#5EB8E8";
const GOLD = "#F2C14E";
const ORANGE = "#E0703A";
const PURPLE = "#7A5BC0";
const STRAW = "#E6C07B";
const NIGHT = "#241536"; // Halloween tile

/** A season of the logo: the tile colour and what is drawn on it (512 viewBox, no tile). */
export interface Season {
  id: string;
  bg: string;
  content: string;
}

/** `x.toFixed(0)` with ties to even, as Python's `f"{x:.0f}"` (including "-0"). */
function f0(x: number): string {
  const lo = Math.floor(x);
  const d = x - lo;
  const n = d < 0.5 ? lo : d > 0.5 ? lo + 1 : lo % 2 === 0 ? lo : lo + 1;
  return n === 0 && x < 0 ? "-0" : String(n);
}

// --- Shapes ----------------------------------------------------------------

function dot(cx: number, cy: number, r: number, fill: string): string {
  return `<circle cx="${cx}" cy="${cy}" r="${r}" fill="${fill}"/>`;
}

/** Play triangle centred optically on (cx, cy), half-height r. */
function play(cx: number, cy: number, r: number, fill: string): string {
  const x0 = cx - r * 0.55;
  return (
    `<path fill="${fill}" stroke="${fill}" stroke-width="${f0(r * 0.25)}" stroke-linejoin="round" ` +
    `d="M${f0(x0)} ${f0(cy - r)} L${f0(x0 + r * 1.5)} ${f0(cy)} L${f0(x0)} ${f0(cy + r)} Z"/>`
  );
}

/** The path and its mirror image across the vertical axis of the 512 tile. */
function mirrored(d: string, fill: string): string {
  return (
    `<path fill="${fill}" d="${d}"/>` +
    `<path fill="${fill}" transform="translate(512 0) scale(-1 1)" d="${d}"/>`
  );
}

// --- The raccoon -----------------------------------------------------------

/** The raccoon alone, full size: pointy rimmed ears, cheek tufts, brows, mask, eyes, muzzle. */
function ratonBody(): string {
  const eyeR = 46;
  const playR = 44;
  const ears =
    `<g stroke="${CREAM}" stroke-width="18" stroke-linejoin="round">` +
    mirrored("M96 236 Q84 110 132 84 Q168 70 228 160 Z", WARM) +
    "</g>" +
    mirrored("M120 196 Q114 128 140 112 Q160 104 194 160 Z", NAVY);
  const head =
    `<path fill="${WARM}" d="M256 135 C380 135 446 220 446 300 L478 338 L434 352 L456 392 L400 396 ` +
    "C360 448 310 462 256 462 C202 462 152 448 112 396 L56 392 L78 352 L34 338 L66 300 " +
    'C66 220 132 135 256 135 Z"/>';
  const brows =
    `<ellipse cx="174" cy="214" rx="74" ry="28" fill="${CREAM}"/>` +
    `<ellipse cx="338" cy="214" rx="74" ry="28" fill="${CREAM}"/>`;
  const blaze = `<path fill="${CREAM}" d="M222 142 Q256 132 290 142 L274 246 L238 246 Z"/>`;
  const mask =
    `<path fill="${NAVY}" d="M64 340 Q66 222 176 214 Q228 212 256 250 Q284 212 336 214 ` +
    'Q446 222 448 340 Q420 392 370 370 Q300 380 256 340 Q212 380 142 370 Q92 392 64 340 Z"/>';
  const eyes =
    dot(176, 294, eyeR, AMBER) +
    dot(176 + eyeR * 0.35, 294 - eyeR * 0.4, eyeR * 0.24, CREAM) +
    play(338, 294, playR, AMBER);
  const muzzle =
    `<path fill="${CREAM}" d="M196 382 Q256 362 316 382 Q302 444 256 450 Q210 444 196 382 Z"/>` +
    `<ellipse cx="256" cy="398" rx="15" ry="10" fill="${NAVY}"/>`;
  return ears + head + brows + blaze + mask + eyes + muzzle;
}

// --- Small motifs ----------------------------------------------------------

function rays(
  cx: number,
  cy: number,
  r0: number,
  r1: number,
  n: number,
  color: string,
  width: number,
  start = 0,
): string {
  let d = "";
  for (let i = 0; i < n; i++) {
    const a = ((start + (i * 360) / n) * Math.PI) / 180;
    d += `M${f0(cx + r0 * Math.cos(a))} ${f0(cy + r0 * Math.sin(a))} L${f0(cx + r1 * Math.cos(a))} ${f0(cy + r1 * Math.sin(a))} `;
  }
  return `<path stroke="${color}" stroke-width="${f0(width)}" stroke-linecap="round" fill="none" d="${d}"/>`;
}

const burst = (cx: number, cy: number, r: number, color: string) =>
  rays(cx, cy, r * 0.35, r, 8, color, r * 0.13);

const flake = (cx: number, cy: number, r: number, color = CREAM) =>
  rays(cx, cy, 0, r, 6, color, r * 0.2, 30);

function heart(cx: number, cy: number, s: number, fill = PINK): string {
  return (
    `<path fill="${fill}" d="M${cx} ${f0(cy + s * 0.9)} C${f0(cx - s * 1.6)} ${f0(cy - s * 0.1)} ` +
    `${f0(cx - s * 0.6)} ${f0(cy - s * 1.2)} ${cx} ${f0(cy - s * 0.35)} C${f0(cx + s * 0.6)} ${f0(cy - s * 1.2)} ` +
    `${f0(cx + s * 1.6)} ${f0(cy - s * 0.1)} ${cx} ${f0(cy + s * 0.9)} Z"/>`
  );
}

function leaf(
  cx: number,
  cy: number,
  s: number,
  rot: number,
  fill: string,
): string {
  return (
    `<g transform="translate(${cx} ${cy}) rotate(${rot})"><path fill="${fill}" ` +
    `d="M0 ${-s} Q${f0(s * 0.8)} ${f0(-s * 0.2)} 0 ${s} Q${f0(-s * 0.8)} ${f0(-s * 0.2)} 0 ${-s} Z"/>` +
    `<path stroke="${NAVY}" stroke-opacity="0.35" stroke-width="3" d="M0 ${f0(-s * 0.7)} V${f0(s * 1.2)}"/></g>`
  );
}

function confetti(items: [number, number, number, string][]): string {
  return items
    .map(
      ([x, y, r, c]) =>
        `<rect x="${x - 7}" y="${y - 4}" width="14" height="8" rx="2" fill="${c}" transform="rotate(${r} ${x} ${y})"/>`,
    )
    .join("");
}

// --- Seasons ---------------------------------------------------------------

/** The raccoon shrunk (x0.82) and lowered to make room, with `behind` under it and `front` over it. */
const dressed = (front = "", behind = "") =>
  `${behind}<g transform="translate(256 318) scale(0.82) translate(-256 -290)">${ratonBody()}</g>${front}`;

const blush = [176, 336]
  .map((x) => `<ellipse cx="${x}" cy="412" rx="22" ry="12" fill="${PINK}"/>`)
  .join("");

const bunnyEar = (cx: number, rot: number) =>
  `<ellipse cx="${cx}" cy="118" rx="28" ry="80" fill="${CREAM}" transform="rotate(${rot} ${cx} 118)"/>` +
  `<ellipse cx="${cx}" cy="124" rx="13" ry="58" fill="${PINK}" transform="rotate(${rot} ${cx} 118)"/>`;

/** The default logo (the raccoon at full size), then the 12 seasonal variants in calendar order from January. */
export const SEASONS: Season[] = [
  { id: "default", bg: NAVY, content: ratonBody() },
  {
    id: "epiphany", // crown of the galette des rois
    bg: NAVY,
    content: dressed(
      `<path fill="${GOLD}" d="M160 204 L152 116 L204 158 L256 96 L308 158 L360 116 L352 204 Z"/>` +
        dot(256, 172, 12, RED) +
        dot(204, 186, 9, BLUE) +
        dot(308, 186, 9, BLUE),
    ),
  },
  {
    id: "winter", // scarf and snowflakes
    bg: NAVY,
    content: dressed(
      `<path fill="${RED}" d="M120 430 Q256 482 392 430 L398 470 Q256 518 114 470 Z"/>` +
        `<path fill="${RED}" d="M324 458 L370 450 L382 512 L336 512 Z"/>` +
        `<path stroke="${CREAM}" stroke-width="7" d="M330 478 L376 470 M334 498 L380 490"/>` +
        flake(90, 96, 26) +
        flake(420, 84, 22) +
        flake(446, 196, 15) +
        flake(66, 206, 14) +
        flake(256, 70, 18),
    ),
  },
  {
    id: "valentine", // blushing cheeks and hearts
    bg: NAVY,
    content: dressed(
      blush +
        heart(96, 104, 30) +
        heart(420, 92, 22) +
        heart(446, 186, 14, RED),
    ),
  },
  {
    id: "april-fools", // a fish stuck on the head
    bg: NAVY,
    content: dressed(
      `<g transform="translate(372 156) rotate(-22)"><ellipse rx="48" ry="24" fill="${BLUE}"/>` +
        `<path fill="${BLUE}" d="M40 0 L74 -24 L74 24 Z"/>` +
        dot(-24, -5, 7, CREAM) +
        dot(-24, -5, 3, NAVY) +
        "</g>",
    ),
  },
  {
    id: "easter", // bunny-ear headband
    bg: NAVY,
    content: dressed("", bunnyEar(214, -14) + bunnyEar(298, 14)),
  },
  {
    id: "music", // headphones
    bg: NAVY,
    content: dressed(
      `<path fill="none" stroke="${RED}" stroke-width="22" stroke-linecap="round" d="M88 330 C88 120 424 120 424 330"/>` +
        `<rect x="62" y="294" width="50" height="80" rx="20" fill="${RED}"/>` +
        `<rect x="400" y="294" width="50" height="80" rx="20" fill="${RED}"/>`,
    ),
  },
  {
    id: "summer", // straw hat
    bg: NAVY,
    content: dressed(
      `<ellipse cx="256" cy="198" rx="178" ry="30" fill="${STRAW}"/>` +
        `<path fill="${STRAW}" d="M178 198 Q180 118 256 114 Q332 118 334 198 Z"/>` +
        `<path fill="${RED}" d="M180 168 L332 168 L334 192 L178 192 Z"/>`,
    ),
  },
  {
    id: "bastille", // 14 July fireworks, blue-white-red
    bg: NAVY,
    content: dressed(
      burst(96, 98, 58, RED) +
        burst(416, 100, 58, BLUE) +
        burst(256, 66, 40, CREAM),
    ),
  },
  {
    id: "autumn", // falling leaves
    bg: NAVY,
    content: dressed(
      leaf(96, 110, 34, -30, ORANGE) +
        leaf(420, 92, 28, 25, GOLD) +
        leaf(440, 214, 22, 70, RED) +
        leaf(186, 178, 26, -50, ORANGE),
    ),
  },
  {
    id: "halloween", // witch hat, purple-night tile
    bg: NIGHT,
    content: dressed(
      `<ellipse cx="256" cy="196" rx="156" ry="26" fill="${PURPLE}"/>` +
        `<path fill="${PURPLE}" d="M176 196 L250 60 Q262 34 300 52 L282 70 L336 196 Z"/>` +
        `<path fill="${ORANGE}" d="M190 170 L322 170 L330 190 L182 190 Z"/>`,
    ),
  },
  {
    id: "christmas", // red hat and snowflakes
    bg: NAVY,
    content: dressed(
      `<path fill="${RED}" d="M144 206 C150 120 230 80 320 92 C372 100 402 132 414 178 L388 188 C376 156 360 140 342 140 L368 206 Z"/>` +
        `<rect x="132" y="182" width="248" height="40" rx="20" fill="${CREAM}"/>` +
        dot(406, 186, 24, CREAM) +
        flake(70, 90, 20) +
        flake(446, 70, 16),
    ),
  },
  {
    id: "new-year", // party hat and confetti
    bg: NAVY,
    content: dressed(
      `<path fill="${PINK}" d="M220 200 L262 66 L304 200 Z"/>` +
        `<path stroke="${CREAM}" stroke-width="8" d="M243 128 L281 128 M231 166 L293 166"/>` +
        dot(262, 62, 15, GOLD) +
        confetti([
          [90, 90, 20, GOLD],
          [140, 60, -30, BLUE],
          [390, 70, 40, PINK],
          [430, 130, -15, GOLD],
          [70, 170, 60, RED],
          [450, 210, 10, BLUE],
          [330, 40, -50, CREAM],
          [190, 110, 35, RED],
        ]),
    ),
  },
];

/** Debug builds only (not a season): the raccoon in a yellow hard hat, the technician. */
export const DEBUG: Season = {
  id: "debug",
  bg: NAVY,
  content: dressed(
    `<path fill="#FFC83D" d="M152 206 Q150 104 256 100 Q362 104 360 206 Z"/>` +
      `<path fill="#FFE07A" d="M240 102 Q256 98 272 102 L270 200 L242 200 Z"/>` +
      `<rect x="124" y="190" width="264" height="28" rx="14" fill="#E0A526"/>`,
  ),
};

/** The standalone logo: the rounded tile of the season with its content. */
export function logoSvg({ bg, content }: Season): string {
  return (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" role="img">' +
    `<title>Viewer</title><rect width="512" height="512" rx="112" fill="${bg}"/>${content}</svg>\n`
  );
}
