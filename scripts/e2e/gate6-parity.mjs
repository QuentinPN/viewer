// Gate 6 parity pass on the real Emby test account, driven over CDP:
// 1. home hero: no « Reprendre » eyebrow, « Reprendre à h:mm » and « Reste … »
//    beside the bar on a resumable title;
// 2. « Playlists » row when the account has one, its page and back;
// 3. Réglages: Accueil (reorder with Monter/Descendre, order restored), Mises à
//    jour (version, one check), À propos, Serveur, Capacités de lecture, Audio et
//    sous-titres (account defaults, « Mode nuit » toggled and restored);
// 4. search, recent searches, « Effacer »;
// 5. a film's detail: Distribution → person page, genre → list, similar titles,
//    collection and trailer when present (never played);
// 6. player: clock, « Fin à », « Options de lecture » (speed, night mode, sync,
//    format, sleep timer, quality). The film is rewound to 0 before leaving, so
//    no resume point is kept.
// Each step is independent: a failure is recorded and the next step runs.
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  detailTitle,
  homeHeading,
  libraryNav,
  playerBadge,
  signIn,
  startScreen,
  testAccount,
  waitForDiagnostics,
  waitForPosition,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate6-parity");
const text = (s) => (s ?? "").replace(/\s+/g, " ").trim();
const call = async (page, cmd, args) => {
  const r = await page.evaluate(
    ([c, a]) =>
      window.__TAURI_INTERNALS__
        .invoke(c, a)
        .then((value) => ({ value }))
        .catch((e) => ({ error: JSON.stringify(e) })),
    [cmd, args],
  );
  if (r.error) throw new Error(`${cmd} : ${r.error}`);
  return r.value;
};
const settingsHeading = (page) =>
  page.getByRole("heading", { name: "Réglages", level: 1 });
const region = (page, id) => page.locator(`section[aria-labelledby=${id}]`);

async function goHome(page) {
  for (let i = 0; i < 3 && !(await libraryNav(page).isVisible()); i++) {
    await page.keyboard.press("Escape");
    await page.waitForTimeout(400);
  }
  await libraryNav(page).locator(".home-link").click();
  await homeHeading(page).waitFor({ timeout: 20000 });
}

async function step(page, name, fn) {
  try {
    await fn();
  } catch (e) {
    check(
      `${name}_error`,
      false,
      e instanceof Error ? e.message.split("\n")[0] : e,
    );
    await shot(page, `${name}-error`).catch(() => {});
  }
}

let app = null;
try {
  app = await launch();
  const page = app.page;
  if ((await startScreen(page)) === "signedOut")
    await signIn(page, account, account.password);
  await homeHeading(page).waitFor({ timeout: 20000 });

  // 1. Hero.
  await step(page, "hero", async () => {
    const hero = page.locator("section.hero");
    await hero.waitFor({ timeout: 20000 });
    const dots = hero.getByRole("button", { name: /^Titre \d+ sur \d+ : / });
    const n = Math.max(1, await dots.count());
    let found = null;
    for (let i = 0; i < n; i++) {
      if ((await dots.count()) > 0) {
        await dots.nth(i).click();
        await page.waitForTimeout(600);
      }
      const play = text(
        await hero
          .locator(".slide button.play")
          .first()
          .textContent()
          .catch(() => ""),
      );
      if (play.startsWith("Reprendre")) {
        found = { i, play };
        break;
      }
    }
    const eyebrow = await hero.evaluate((h) =>
      [...h.querySelectorAll(".slide *")].some(
        (e) => e.children.length === 0 && e.textContent.trim() === "Reprendre",
      ),
    );
    check("hero_no_reprendre_eyebrow", !eyebrow);
    if (!found) {
      check(
        "hero_resumable",
        true,
        "NOT APPLICABLE: aucun titre à reprendre dans le hero",
      );
      await shot(page, "hero");
      return;
    }
    check(
      "hero_resume_label",
      /^Reprendre à (\d+:)?\d{1,2}:\d{2}$/.test(found.play),
      `${found.play} (diapositive ${found.i + 1}/${n})`,
    );
    const left = text(
      await hero
        .locator(".slide .remaining .left")
        .textContent()
        .catch(() => ""),
    );
    const bar = await hero
      .locator(".slide .remaining [role=progressbar]")
      .count();
    check("hero_remaining", bar === 1 && /^Reste .+/.test(left), left);
    await shot(page, "hero-resume");
  });

  // 2. Playlists.
  await step(page, "playlists", async () => {
    await page.waitForTimeout(1500);
    const row = page.locator("section[aria-labelledby=row-playlists]");
    if ((await row.count()) === 0) {
      check("playlists_row", true, "NOT APPLICABLE: pas de rangée Playlists");
      await shot(page, "home-no-playlists");
      return;
    }
    await row.scrollIntoViewIfNeeded();
    const card = row.locator("button.card[data-playlist-id]").first();
    await card.waitFor({ timeout: 15000 });
    check(
      "playlists_row",
      true,
      `${await row.locator("button.card").count()} playlist(s)`,
    );
    await shot(page, "home-playlists");
    await card.click();
    await page.locator("#playlist-title").waitFor({ timeout: 15000 });
    await page.waitForTimeout(1000);
    const name = text(await page.locator("#playlist-title").textContent());
    const summary = text(
      await page
        .locator(".playlist .summary")
        .textContent()
        .catch(() => ""),
    );
    check("playlist_page", name.length > 0, `${name} — ${summary}`);
    await shot(page, "playlist-page");
    await page.locator(".playlist button.back").click();
    await homeHeading(page).waitFor({ timeout: 15000 });
    check(
      "playlist_back_home",
      (await page.locator("#playlist-title").count()) === 0,
    );
  });

  // 3. Settings.
  await step(page, "settings", async () => {
    await goHome(page);
    await libraryNav(page)
      .getByRole("button", { name: "Réglages", exact: true })
      .click();
    await settingsHeading(page).waitFor({ timeout: 10000 });
    for (const [id, label] of [
      ["settings-home", "Accueil"],
      ["settings-updates", "Mises à jour"],
      ["settings-about", "À propos"],
      ["settings-server", "Serveur"],
      ["settings-capabilities", "Capacités de lecture"],
      ["settings-subtitles", "Audio et sous-titres"],
    ])
      check(`section_${id}`, (await region(page, id).count()) === 1, label);

    // Accueil: reorder then restore.
    const home = region(page, "settings-home");
    const order = () =>
      home
        .locator("ol.rows > li")
        .evaluateAll((l) => l.map((e) => e.dataset.key));
    const before = await order();
    const shown = await home
      .locator("ol.rows > li")
      .evaluateAll((l) =>
        l.map(
          (e) =>
            `${e.querySelector(".name")?.textContent.trim()}:${e.querySelector(".state")?.textContent.trim()}`,
        ),
      );
    check("home_rows_listed", before.length > 1, shown.join(", "));
    await home.scrollIntoViewIfNeeded();
    await shot(page, "settings-home-rows");
    await home
      .locator("ol.rows > li")
      .first()
      .locator("button.move.down")
      .click();
    await page.waitForTimeout(400);
    const moved = await order();
    check(
      "home_rows_descendre",
      moved[0] === before[1] && moved[1] === before[0],
      moved.join(","),
    );
    await shot(page, "settings-home-moved");
    await home
      .locator(`ol.rows > li[data-key="${before[0]}"] button.move.up`)
      .click();
    await page.waitForTimeout(400);
    const restored = await order();
    check(
      "home_rows_restored",
      restored.join() === before.join(),
      restored.join(","),
    );

    // Mises à jour.
    const upd = region(page, "settings-updates");
    const lead = text(await upd.locator(".lead").textContent());
    check(
      "updates_version",
      /Viewer version \d+\.\d+\.\d+/.test(lead),
      lead.split(".")[0],
    );
    const btn = upd.getByRole("button", {
      name: "Rechercher des mises à jour",
    });
    check("updates_button", (await btn.count()) === 1);
    await btn.click();
    await page
      .waitForFunction(
        () => {
          const s = document.querySelector(
            "section[aria-labelledby=settings-updates] [role=status]",
          );
          return (
            s &&
            s.textContent.trim() &&
            !/Recherche en cours/.test(s.textContent)
          );
        },
        null,
        { timeout: 30000 },
      )
      .catch(() => {});
    const msg = text(await upd.locator("[role=status]").textContent());
    check("updates_check_message", msg.length > 0, msg);
    await upd.scrollIntoViewIfNeeded();
    await shot(page, "settings-updates");

    // À propos, Serveur, Capacités.
    for (const id of [
      "settings-about",
      "settings-server",
      "settings-capabilities",
    ]) {
      const t = text(await region(page, id).textContent());
      check(`${id}_content`, t.length > 20, t.slice(0, 160));
    }
    await region(page, "settings-server").scrollIntoViewIfNeeded();
    await shot(page, "settings-about-server");

    // Audio et sous-titres.
    const subs = region(page, "settings-subtitles");
    await subs.scrollIntoViewIfNeeded();
    await page
      .waitForFunction(
        () =>
          !/lecture…/.test(
            document.querySelector("#account-audio")?.textContent ?? "",
          ),
        null,
        { timeout: 10000 },
      )
      .catch(() => {});
    const audioLine = text(await subs.locator("#account-audio").textContent());
    const subLine = text(
      await subs.locator("#account-subtitles").textContent(),
    );
    const selects = await subs
      .locator("select")
      .evaluateAll((s) =>
        s
          .slice(0, 2)
          .map((e) => e.options[e.selectedIndex]?.textContent.trim()),
      );
    const optionTexts = await subs
      .locator("select")
      .nth(1)
      .evaluate((e) => [...e.options].map((o) => o.textContent.trim()));
    check(
      "audio_account_default",
      /compte Emby/i.test(audioLine) && !/lecture…/.test(audioLine),
      `${selects[0]} — ${audioLine}`,
    );
    check(
      "subtitles_account_default",
      /compte Emby/i.test(subLine),
      `${selects[1]} — ${subLine}`,
    );
    check(
      "subtitle_option_comme_le_compte",
      optionTexts.includes("Comme le compte Emby"),
      optionTexts.join(" | "),
    );
    const night = subs
      .locator("label.check", { hasText: "Mode nuit" })
      .locator("input");
    const was = await night.isChecked();
    await night.click();
    await page.waitForTimeout(400);
    const toggled = await night.isChecked();
    await shot(page, "settings-subtitles-night-toggled");
    await night.click();
    await page.waitForTimeout(400);
    const back = await night.isChecked();
    check(
      "night_mode_toggle",
      toggled === !was && back === was,
      `initial ${was}, basculé ${toggled}, restauré ${back}`,
    );
    await shot(page, "settings-subtitles");
  });

  // 4. Search and recent searches.
  await step(page, "search", async () => {
    const term = "the";
    const field = page.locator("#search-field");
    await field.fill(term);
    await page.waitForTimeout(2500);
    await shot(page, "search-results");
    await field.fill("");
    await field.focus();
    await page.waitForTimeout(600);
    const recent = page.locator(".recent[aria-labelledby=recent-title]");
    await recent.waitFor({ timeout: 5000 }).catch(() => {});
    const items = await recent
      .locator("ul li button")
      .allTextContents()
      .catch(() => []);
    check(
      "recent_lists_term",
      items.map(text).includes(term),
      items.map(text).join(", "),
    );
    await shot(page, "search-recent");
    if ((await recent.count()) > 0) {
      await recent.getByRole("button", { name: "Effacer" }).click();
      await page.waitForTimeout(500);
      await field.focus();
      await page.waitForTimeout(400);
      check("recent_cleared", (await page.locator(".recent").count()) === 0);
      await shot(page, "search-recent-cleared");
    }
    await page.keyboard.press("Escape");
  });

  // 5. A film's detail page.
  let filmCard = null;
  await step(page, "detail", async () => {
    await goHome(page);
    const libs = libraryNav(page).locator("ul.libraries button");
    const kinds = await libs.evaluateAll((els) =>
      els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
    );
    await libs.nth(kinds.indexOf("Films")).click();
    await cards(page).first().waitFor({ timeout: 20000 });
    for (let n = 0; n < Math.min(8, await cards(page).count()); n++) {
      await cards(page).nth(n).click();
      await detailTitle(page).waitFor({ timeout: 20000 });
      await page.waitForTimeout(1500);
      if ((await page.locator("section.cast button.person").count()) > 0) {
        filmCard = n;
        break;
      }
      await page.keyboard.press("Escape");
      await cards(page).first().waitFor({ timeout: 10000 });
    }
    if (filmCard === null)
      throw new Error("aucun film avec distribution cliquable");
    const title = text(
      await detailTitle(page).evaluate(
        (e) => e.textContent.trim() || e.querySelector("img")?.alt || "",
      ),
    );
    const castHead = text(await page.locator("section.cast h2").textContent());
    check(
      "detail_distribution",
      castHead === "Distribution",
      `${title}: ${castHead}`,
    );
    await shot(page, "detail");

    const person = page.locator("section.cast button.person").first();
    const personName = text(await person.locator(".person-name").textContent());
    await person.click();
    await page.locator("#titles-page-title").waitFor({ timeout: 15000 });
    await page.waitForTimeout(1500);
    check(
      "person_page",
      text(await page.locator("#titles-page-title").textContent()).includes(
        personName,
      ),
      personName,
    );
    await shot(page, "person-page");
    await page.locator(".titles-page button.back").click();
    await page
      .locator("#titles-page-title")
      .waitFor({ state: "detached", timeout: 10000 });
    check("person_back", await detailTitle(page).isVisible());

    const genre = page.locator("ul.genres button").first();
    if ((await genre.count()) === 0) {
      check("genre_page", true, "NOT APPLICABLE: pas de genre cliquable");
    } else {
      const g = text(await genre.textContent());
      await genre.click();
      await page.locator("#titles-page-title").waitFor({ timeout: 15000 });
      await page.waitForTimeout(1500);
      const t = text(await page.locator("#titles-page-title").textContent());
      check("genre_page", t.includes(g), `${g} → ${t}`);
      await shot(page, "genre-page");
      await page.locator(".titles-page button.back").click();
      await page
        .locator("#titles-page-title")
        .waitFor({ state: "detached", timeout: 10000 });
      check("genre_back", await detailTitle(page).isVisible());
    }

    await page.waitForTimeout(2000);
    const rows = await page.locator("section.title-row h2").allTextContents();
    check(
      "similar_row",
      rows.map(text).includes("Titres similaires"),
      rows.map(text).join(" | "),
    );
    const coll = rows.map(text).filter((r) => r.startsWith("Collection : "));
    check(
      "collection_row",
      true,
      coll.length ? coll.join(", ") : "NOT APPLICABLE: aucune collection",
    );
    const trailer = await page.locator(".detail button.trailer").count();
    check(
      "trailer_button",
      true,
      trailer
        ? "Bande-annonce présente (non lue)"
        : "NOT APPLICABLE: pas de bande-annonce",
    );
    await page
      .locator("section.title-row")
      .last()
      .scrollIntoViewIfNeeded()
      .catch(() => {});
    await shot(page, "detail-rows");
    await page.keyboard.press("Escape");
    await cards(page).first().waitFor({ timeout: 10000 });
  });

  // 6. Player.
  await step(page, "player", async () => {
    let film = null;
    for (let n = 0; n < Math.min(12, await cards(page).count()); n++) {
      const id = await cards(page).nth(n).getAttribute("data-item-id");
      const d = await call(page, "emby_item_detail", { itemId: id });
      if (d.resume === null) {
        film = { n, id };
        break;
      }
    }
    if (!film) throw new Error("aucun film sans point de reprise");
    await cards(page).nth(film.n).click();
    await detailTitle(page).waitFor({ timeout: 20000 });
    const name = text(
      await detailTitle(page).evaluate(
        (e) => e.textContent.trim() || e.querySelector("img")?.alt || "",
      ),
    );
    await page.locator(".detail button.play").first().click();
    await playerBadge(page).waitFor({ timeout: 20000 });
    const started = await waitForPosition(page, 1, 20000);
    check(
      "player_plays",
      started?.ok && started.timePos > 1,
      `${name} (${film.id})`,
    );
    await page.mouse.move(400, 300);
    await page.mouse.move(420, 320);
    await page.waitForTimeout(500);
    const clock = text(
      await page
        .locator(".player .clock")
        .textContent()
        .catch(() => ""),
    );
    check("player_clock", /^\d{1,2}:\d{2}$/.test(clock), clock);
    const ends = await page
      .locator(".player .end-times")
      .textContent()
      .catch(() => "");
    check("player_fin_a", /Fin à \d{1,2}:\d{2}/.test(text(ends)), text(ends));
    await shot(page, "player-controls");
    await page
      .locator(".player")
      .getByRole("button", { name: "Options de lecture" })
      .click();
    const dlg = page.getByRole("dialog", { name: "Options de lecture" });
    await dlg.waitFor({ timeout: 5000 });
    const legends = (await dlg.locator("legend").allTextContents()).map(text);
    for (const l of [
      "Vitesse",
      "Son",
      "Synchronisation",
      "Format de l'image",
      "Minuterie",
    ])
      check(`options_${l}`, legends.includes(l), legends.join(" | "));
    check("options_mode_nuit", (await dlg.getByText("Mode nuit").count()) > 0);
    check("options_qualite", legends.includes("Qualité"), legends.join(" | "));
    await shot(page, "player-options");
    await dlg.evaluate((d) => d.scrollTo(0, d.scrollHeight)).catch(() => {});
    await shot(page, "player-options-bottom");
    await page.keyboard.press("Escape");
    await call(page, "player_command", {
      command: { type: "seekAbsolute", seconds: 0 },
    });
    const rewound = await waitForDiagnostics(
      page,
      (d) => (d.timePos ?? 99) < 3 && (d.timePos ?? 0) > 0,
    );
    check("player_rewound", rewound?.ok && rewound.timePos < 3);
    await page.keyboard.press("Escape");
    await playerBadge(page).waitFor({ state: "detached", timeout: 15000 });
    await page.waitForTimeout(1500);
    const after = await call(page, "emby_item_detail", { itemId: film.id });
    check(
      "no_resume_kept",
      after.resume === null,
      JSON.stringify(after.resume),
    );
  });

  check("exit_code", (await app.close()) === 0);
  app = null;
} catch (e) {
  check(
    "scenario_error",
    false,
    e instanceof Error ? e.message.split("\n")[0] : e,
  );
  if (app) await shot(app.page, "error").catch(() => {});
} finally {
  if (app) await app.close().catch(() => {});
  finish();
}
