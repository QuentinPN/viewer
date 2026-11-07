// Guards the scenarios against a Vite dev server serving a component's old CSS
// while its markup is current (seen twice, not reproduced on demand; a restart of
// `npm run dev` fixed it). The page itself fetches each component's style module,
// so the check sees the server the WebView uses. Each one-line declaration of a
// `<style>` block must be in what is served: svelte-check fails on unused
// selectors, so Svelte drops none of them. `animation` is left out: Svelte renames
// the scoped keyframes in it.
import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";

const DECLARATION = /^\s*([a-z-]+:\s[^;{}"'\\]+;)\s*$/;

function svelteFiles(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name);
    if (e.isDirectory()) return svelteFiles(path);
    return e.name.endsWith(".svelte") ? [path] : [];
  });
}

/** One-line declarations of the `<style>` block of each component, by URL path. */
export function expectedStyles(root) {
  const out = [];
  for (const file of svelteFiles(join(root, "src"))) {
    const style = /<style>([\s\S]*?)<\/style>/.exec(readFileSync(file, "utf8"));
    if (!style) continue;
    const declarations = style[1]
      .split(/\r?\n/)
      .map((line) => DECLARATION.exec(line)?.[1])
      .filter((d) => d && !d.startsWith("animation"));
    const path = relative(root, file).replaceAll("\\", "/");
    out.push({ path, declarations });
  }
  return out;
}

/** Components whose served CSS misses declarations of the source, with one example. */
export async function staleStyles(page, root) {
  const expected = expectedStyles(root);
  return page.evaluate(async (list) => {
    const stale = [];
    for (const { path, declarations } of list) {
      const res = await fetch(`/${path}?svelte&type=style&lang.css`);
      const served = await res.text();
      const missing = declarations.find((d) => !served.includes(d));
      if (!res.ok || missing)
        stale.push(`${path}: ${res.ok ? missing : `HTTP ${res.status}`}`);
    }
    return stale;
  }, expected);
}
