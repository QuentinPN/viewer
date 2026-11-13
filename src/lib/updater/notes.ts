/** One line of release notes, shown as text (never as HTML). */
export interface NoteLine {
  kind: "heading" | "bullet" | "text";
  text: string;
}

/** Inline markdown reduced to its text: links, emphasis, code. */
function plain(text: string): string {
  return text
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/(\*\*|__)(.+?)\1/g, "$2")
    .replace(/(\*|_)(\S(?:.*?\S)?)\1/g, "$2")
    .replace(/`([^`]*)`/g, "$1")
    .replace(/<[^>]*>/g, "")
    .trim();
}

/** Markdown release notes as lines of plain text: headings, bullets, paragraphs. */
export function formatNotes(markdown: string): NoteLine[] {
  const lines: NoteLine[] = [];
  for (const raw of markdown.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === "" || /^([-*_])\1{2,}$/.test(line)) continue;
    const heading = /^#{1,6}\s+(.*)$/.exec(line);
    const bullet = /^(?:[-*+]|\d+[.)])\s+(.*)$/.exec(line);
    const kind = heading ? "heading" : bullet ? "bullet" : "text";
    const text = plain(heading?.[1] ?? bullet?.[1] ?? line);
    if (text !== "") lines.push({ kind, text });
  }
  return lines;
}
