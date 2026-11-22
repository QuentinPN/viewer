// Releases on the self-hosted Gitea. The code is the `origin`
// remote; releases and update manifests go to the public `<repo>-releases`
// next to it. The token comes from GITEA_TOKEN in the environment, else from the
// gitignored debug.env at the root of the main checkout. It is never printed.
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { basename, resolve } from "node:path";

export type Release = { id: number; url: string; fileUrl: string };

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
  const { api, page } = giteaRepo(remote.replace(/(\.git)?\/?$/, "-releases"));
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
    /** Page of the public releases repository. */
    page,

    /** Creates the release (and its tag on the releases repository's main), or
     *  reuses it to redo a failed publish; uploads the file unless the
     *  same bytes are already there (a different one is replaced), then downloads
     *  it back without the token, as the apps will, to check its SHA-256. */
    async publish(
      tag: string,
      title: string,
      notes: string,
      file: string,
      sha256: string,
    ): Promise<Release> {
      const found = await fetch(`${api}/releases/tags/${tag}`, {
        headers: auth,
      });
      if (!found.ok && found.status !== 404)
        throw new Error(`Gitea release ${tag} : ${found.status}`);
      type Asset = { id: number; name: string; browser_download_url: string };
      const { id, assets = [] } = (await (found.ok
        ? found
        : await call("/releases", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              tag_name: tag,
              target_commitish: "main",
              name: title,
              body: notes,
            }),
          })
      ).json()) as { id: number; assets?: Asset[] };
      const name = basename(file);
      const online = async (url: string) => {
        const back = await fetch(url);
        if (!back.ok)
          throw new Error(`téléchargement de contrôle : ${back.status}`);
        return createHash("sha256")
          .update(Buffer.from(await back.arrayBuffer()))
          .digest("hex");
      };
      let asset = assets.find((a) => a.name === name);
      if (asset && (await online(asset.browser_download_url)) !== sha256) {
        await call(`/releases/${id}/assets/${asset.id}`, { method: "DELETE" });
        asset = undefined;
      }
      if (!asset) {
        const form = new FormData();
        form.append("attachment", new Blob([readFileSync(file)]), name);
        const up = await call(
          `/releases/${id}/assets?name=${encodeURIComponent(name)}`,
          { method: "POST", body: form },
        );
        asset = (await up.json()) as Asset;
      }
      const hash = await online(asset.browser_download_url);
      if (hash !== sha256)
        throw new Error(`le fichier en ligne ne correspond pas : ${hash}`);
      return {
        id,
        url: `${page}/releases/tag/${tag}`,
        fileUrl: asset.browser_download_url,
      };
    },

    /** Writes `path` on main of the releases repository (update manifests),
     *  then reads it back without the token. */
    async writeFile(path: string, content: string, message: string) {
      const current = await fetch(`${api}/contents/${path}?ref=main`, {
        headers: auth,
      });
      if (!current.ok && current.status !== 404)
        throw new Error(`Gitea ${path} : ${current.status}`);
      const sha = current.ok
        ? ((await current.json()) as { sha: string }).sha
        : undefined;
      await call(`/contents/${path}`, {
        method: sha ? "PUT" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          branch: "main",
          message,
          content: Buffer.from(content).toString("base64"),
          ...(sha ? { sha } : {}),
        }),
      });
      const raw = await fetch(`${page}/raw/branch/main/${path}`);
      if (!raw.ok || (await raw.text()) !== content)
        throw new Error(`${path} illisible sans jeton après écriture`);
    },
  };
}
