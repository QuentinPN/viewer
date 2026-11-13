// Releases on the self-hosted Gitea (ADR 0111). The repository is the `origin`
// remote; the token comes from GITEA_TOKEN in the environment, else from the
// gitignored debug.env at the root of the main checkout. It is never printed.
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { basename, resolve } from "node:path";

export type Release = { id: number; url: string };

/** `http://host/Owner/Repo(.git)` → API base and page URL. */
export function giteaRepo(remote: string): { api: string; page: string } {
  const m = /^(https?:\/\/[^/]+)\/([^/]+)\/([^/]+?)(?:\.git)?\/?$/.exec(remote);
  if (!m) throw new Error(`remote origin non reconnu : ${remote}`);
  const [, host, owner, repo] = m;
  return {
    api: `${host}/api/v1/repos/${owner}/${repo}`,
    page: `${host}/${owner}/${repo}`,
  };
}

function token(root: string): string {
  if (process.env.GITEA_TOKEN) return process.env.GITEA_TOKEN;
  const common = execFileSync("git", ["rev-parse", "--git-common-dir"], {
    cwd: root,
    encoding: "utf8",
  }).trim();
  const env = resolve(root, common, "..", "debug.env");
  const line = existsSync(env)
    ? readFileSync(env, "utf8")
        .split(/\r?\n/)
        .find((l) => l.startsWith("GITEA_TOKEN="))
    : undefined;
  if (!line) throw new Error("GITEA_TOKEN absent (environnement ou debug.env)");
  return line.slice("GITEA_TOKEN=".length).trim();
}

export function gitea(root: string) {
  const remote = execFileSync("git", ["remote", "get-url", "origin"], {
    cwd: root,
    encoding: "utf8",
  }).trim();
  const { api, page } = giteaRepo(remote);
  const auth = { Authorization: `token ${token(root)}` };

  async function call(path: string, init: RequestInit = {}) {
    const res = await fetch(`${api}${path}`, {
      ...init,
      headers: { ...auth, ...init.headers },
    });
    if (!res.ok)
      throw new Error(`Gitea ${init.method ?? "GET"} ${path} : ${res.status}`);
    return res;
  }

  return {
    async exists(tag: string): Promise<boolean> {
      const res = await fetch(`${api}/releases/tags/${tag}`, { headers: auth });
      if (res.status === 404) return false;
      if (!res.ok) throw new Error(`Gitea release ${tag} : ${res.status}`);
      return true;
    },

    /** Creates the release on an already pushed tag, uploads the file and
     *  downloads it back to check its SHA-256. */
    async publish(
      tag: string,
      title: string,
      notes: string,
      file: string,
      sha256: string,
    ): Promise<Release> {
      const created = await call("/releases", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tag_name: tag, name: title, body: notes }),
      });
      const { id } = (await created.json()) as { id: number };
      const name = basename(file);
      const form = new FormData();
      form.append("attachment", new Blob([readFileSync(file)]), name);
      const up = await call(
        `/releases/${id}/assets?name=${encodeURIComponent(name)}`,
        { method: "POST", body: form },
      );
      const { browser_download_url } = (await up.json()) as {
        browser_download_url: string;
      };
      const back = await fetch(browser_download_url, { headers: auth });
      if (!back.ok)
        throw new Error(`téléchargement de contrôle : ${back.status}`);
      const online = createHash("sha256")
        .update(Buffer.from(await back.arrayBuffer()))
        .digest("hex");
      if (online !== sha256)
        throw new Error(`le fichier en ligne ne correspond pas : ${online}`);
      return { id, url: `${page}/releases/tag/${tag}` };
    },
  };
}
