// Seasonal logo calendar. The rules and the shared test vectors live in
// contracts/seasons; the Android TV app has the same function.
export const SEASON_IDS = [
  "default",
  "new-year",
  "epiphany",
  "winter",
  "valentine",
  "april-fools",
  "easter",
  "music",
  "summer",
  "bastille",
  "autumn",
  "halloween",
  "christmas",
] as const;

export type SeasonId = (typeof SEASON_IDS)[number];

/** Gregorian Easter Sunday (anonymous Gregorian / Meeus algorithm). */
function easterSunday(year: number): { month: number; day: number } {
  const a = year % 19;
  const b = Math.floor(year / 100);
  const c = year % 100;
  const d = Math.floor(b / 4);
  const e = b % 4;
  const f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3);
  const h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4);
  const k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7;
  const m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31);
  const day = ((h + l - 7 * m + 114) % 31) + 1;
  return { month, day };
}

const DAY_MS = 86_400_000;

/**
 * The season of a local calendar date (month 1 to 12). Where periods overlap
 * the most specific one wins: april-fools, music, bastille, valentine,
 * new-year, halloween, easter, epiphany, christmas, winter, summer, autumn.
 */
export function seasonFor(year: number, month: number, day: number): SeasonId {
  const md = month * 100 + day;
  if (md === 401) return "april-fools";
  if (md === 621) return "music";
  if (md === 714) return "bastille";
  if (md >= 210 && md <= 214) return "valentine";
  if (md >= 1227 || md === 101) return "new-year";
  if (md >= 1024 && md <= 1031) return "halloween";

  // Holy Week Monday (Easter - 6 days) to Easter Monday (Easter + 1 day)
  const easter = easterSunday(year);
  const easterDay = Date.UTC(year, easter.month - 1, easter.day) / DAY_MS;
  const today = Date.UTC(year, month - 1, day) / DAY_MS;
  if (today >= easterDay - 6 && today <= easterDay + 1) return "easter";

  if (md >= 101 && md <= 115) return "epiphany";
  if (md >= 1201 && md <= 1226) return "christmas";
  // Jan 16 to the end of February (month 2 whatever its length)
  if ((month === 1 && day >= 16) || month === 2) return "winter";
  if (month === 7 || month === 8) return "summer";
  if (month === 9 || month === 10) return "autumn";
  return "default";
}

/** The season of [d] in the local time zone. */
export function seasonForDate(d: Date): SeasonId {
  return seasonFor(d.getFullYear(), d.getMonth() + 1, d.getDate());
}
