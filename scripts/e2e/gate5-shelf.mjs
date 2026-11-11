// Pre-connection shelf (ADR 0103) on the real Emby test account, driven over CDP:
// - a tile without token opens the password card, user prefilled, focus on password;
// - « Changer de serveur » keeps the token: OK on the tile reopens without password;
// - « Gérer »: the confirmation focuses « Annuler », Annuler keeps the tile;
// - the manual card opens with the last address and « Retour » goes back;
// - « Oublier ce serveur » removes the tile and its token, the ghost tile shows.
import { spawnSync } from "node:child_process";
import { launch } from "./app.mjs";
import {
  accountAction,
  createReport,
  libraryNav,
  signIn,
  signInHeading,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate5-shelf");

const shelf = (page) => page.locator("main.screen > section.shelf");
const tiles = (page) =>
  page.locator('ul.tiles[aria-label="Serveurs enregistrés"] button.tile');
const ownTile = (page, userId) =>
  page.locator(`button.tile[data-user-id="${userId}"]`);
const button = (page, name) => page.getByRole("button", { name, exact: true });
const focusedName = (page) =>
  page.evaluate(
    () =>
      document.activeElement?.getAttribute("aria-label") ??
      document.activeElement?.textContent?.trim() ??
      document.activeElement?.id,
  );
/** Debug-build vault entry of this user (target "<serverId>/<userId>.Viewer-debug"). */
const vaultHas = (userId) =>
  spawnSync("cmdkey", ["/list"], { encoding: "utf8" })
    .stdout.split(/\r?\n/)
    .some((l) => l.trim().endsWith(`/${userId}.Viewer-debug`));

async function toShelf(page) {
  await accountAction(page, "Changer de serveur");
  await shelf(page).waitFor({ timeout: 15000 });
}

let app = await launch();
try {
  const { page } = app;
  if ((await startScreen(page)) === "signedIn") {
    await accountAction(page, "Se déconnecter");
    await signInHeading(page).waitFor({ timeout: 15000 });
  }
  await shelf(page).waitFor({ timeout: 15000 });
  if ((await tiles(page).count()) === 0) {
    await signIn(page, account, account.password);
    await libraryNav(page).waitFor({ timeout: 20000 });
    await accountAction(page, "Se déconnecter");
    await shelf(page).waitFor({ timeout: 15000 });
  }
  const tile = tiles(page).filter({ hasText: account.user }).first();
  const userId = await tile.getAttribute("data-user-id");
  check("shelf_tile", userId, `${await tile.getAttribute("aria-label")}`);
  check(
    "signed_out_tile_without_token",
    (await tile.getAttribute("data-access")) === "signedOut" &&
      !vaultHas(userId),
    await tile.getAttribute("data-access"),
  );
  await shot(page, "shelf-signed-out");

  // Tile without token: password card over the shelf.
  await tile.click();
  await page.locator("#password").waitFor({ timeout: 10000 });
  check(
    "password_card_prefilled",
    (await page.locator("#username").inputValue()) === account.user &&
      (await page.locator("#server-url").count()) === 0,
    await signInHeading(page).textContent(),
  );
  check(
    "password_focused",
    await page.evaluate(() => document.activeElement?.id === "password"),
  );
  await shot(page, "password-card");
  await page.locator("#password").fill(account.password);
  await button(page, "Se connecter").click();
  await libraryNav(page).waitFor({ timeout: 20000 });
  check("signed_in_from_tile", vaultHas(userId));

  // « Changer de serveur » keeps the token; the tile reopens without password.
  await toShelf(page);
  const kept = ownTile(page, userId);
  check(
    "change_server_keeps_token",
    (await kept.getAttribute("data-access")) === "session" && vaultHas(userId),
    await kept.getAttribute("data-access"),
  );
  await shot(page, "shelf-session");

  // « Gérer », then Annuler.
  await button(page, "Gérer").click();
  await button(page, "Terminer").waitFor({ timeout: 5000 });
  check(
    "manage_mode",
    (await kept.getAttribute("class")).includes("manage") &&
      /^Oublier /.test(await kept.getAttribute("aria-label")),
    await kept.getAttribute("aria-label"),
  );
  await kept.click();
  const dialog = page.locator('.dialog[role="alertdialog"]');
  await dialog.waitFor({ timeout: 5000 });
  check(
    "confirm_cancel_focused",
    (await focusedName(page)) === "Annuler",
    await page.locator("#forget-title").textContent(),
  );
  await shot(page, "forget-confirm");
  await button(page, "Annuler").click();
  await dialog.waitFor({ state: "detached", timeout: 5000 });
  check("cancel_keeps_tile", (await kept.count()) === 1 && vaultHas(userId));
  await button(page, "Terminer").click();
  await button(page, "Gérer").waitFor({ timeout: 5000 });

  // OK on the tile: no password asked.
  await ownTile(page, userId).click();
  await libraryNav(page).waitFor({ timeout: 20000 });
  check("resume_without_password", true);

  // Manual card, then Retour.
  await toShelf(page);
  await button(page, "Se connecter manuellement").click();
  await page.locator("#server-url").waitFor({ timeout: 5000 });
  // The last address is read after the card opens.
  await page
    .waitForFunction(() => document.querySelector("#server-url")?.value, null, {
      timeout: 5000,
    })
    .catch(() => {});
  const prefilled = await page.locator("#server-url").inputValue();
  check(
    "manual_card",
    prefilled === (account.url ?? prefilled) && prefilled.length > 0,
    prefilled,
  );
  await shot(page, "manual-card");
  await button(page, "Retour").click();
  await page.locator("#server-url").waitFor({ state: "detached" });
  check("manual_back_to_shelf", (await tiles(page).count()) > 0);

  // Forget: the tile and its token go, the ghost tile shows.
  await button(page, "Gérer").click();
  await ownTile(page, userId).click();
  await dialog.waitFor({ timeout: 5000 });
  await button(page, "Oublier ce serveur").click();
  await dialog.waitFor({ state: "detached", timeout: 10000 });
  await ownTile(page, userId).waitFor({ state: "detached", timeout: 10000 });
  check("forget_removes_tile", (await ownTile(page, userId).count()) === 0);
  check("forget_removes_token", !vaultHas(userId));
  if ((await tiles(page).count()) === 0) {
    check(
      "ghost_tile",
      await page
        .locator("li.tile.ghost", { hasText: "Aucun serveur pour l'instant" })
        .isVisible(),
    );
    check(
      "manual_primary",
      (
        await button(page, "Se connecter manuellement").getAttribute("class")
      ).includes("primary"),
    );
  }
  await shot(page, "after-forget");

  // Back to the starting state: a tile without token, so the address stays for
  // the other scenarios.
  await signIn(page, { ...account, url: prefilled }, account.password);
  await libraryNav(page).waitFor({ timeout: 20000 });
  await accountAction(page, "Se déconnecter");
  await shelf(page).waitFor({ timeout: 15000 });
  check(
    "restored_tile",
    (await ownTile(page, userId).getAttribute("data-access")) === "signedOut" &&
      !vaultHas(userId),
  );

  check("exit_code", (await app.close()) === 0);
  app = null;
} catch (e) {
  check("scenario", false, e.stack ?? e);
} finally {
  if (app) await app.close().catch(() => {});
  finish();
}
