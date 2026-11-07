# Viewer — Design and UX specification

## Experience

Cinematic desktop media browser in the spirit of the Apple TV app (ADR 0031; it replaces the earlier Infuse inspiration): black canvas, large artwork, translucent floating materials, focus shown by lifting the item, calm spring motion. Original Viewer identity, no Apple assets or fonts. Windows keyboard shortcuts only (Ctrl, Alt, Maj, Win; never ⌘ or ⌥). French copy. Mockups: the design canvas « Viewer — redesign Apple TV » (7 screens at 1440×900).

## Design tokens (`src/app.css`)

- Background `#000`; solid surfaces `#1C1C1E` / `#2C2C2E` (skeletons, cards without artwork).
- Text `#F5F5F7`, secondary `rgb(235 235 245 / 0.7)`, muted `rgb(235 235 245 / 0.55)` (≥ 4.5:1 on black).
- Materials for floating UI (navigation, panels, secondary buttons): `rgb(40 40 46 / 0.6)` with `blur(20px) saturate(180%)`, thin `rgb(255 255 255 / 0.08)` border. Keep blur off large scrolling areas.
- Primary button: white `#F5F5F7` with black text. Secondary: glass `rgb(120 120 128 / 0.36)`. Accent `#8FD3F5` (from the logo) only for focus rings, links and small markers; success `#64C69A`; danger `#FF6961`.
- Radius 10/14/22 px, pills for navigation; spacing on a 4 px scale.
- Focus: cards lift (×1.06, shadow, light ring) on a 320 ms spring; the accent outline stays for the keyboard. Reduced motion: no scale and no transition, the ring stays.
- Type: Segoe UI Variable Display for headings (−0.02em, line-height 1.05), Segoe UI Variable Text for the rest; no bundled fonts.

## Main screens

- Window (ADR 0029): no Windows title bar. A 32 px bar of the app's background holds Windows' three caption buttons on the right (close turns red on hover); its empty area drags the window, double-click maximizes. In the player it sits in the top gradient with no background and fades with the controls; none in fullscreen.
- Icon: a white "U" holding a blue play triangle on a dark rounded tile, source `src-tauri/icons/source/logo.svg`.

- Navigation (ADR 0031): a translucent pill floats at the top center over the content, which scrolls under it: search field (icon, widens on focus), "Accueil", one entry per library, "Réglages" (gear) and the account (initial). The current entry is a white pill with black text. Ctrl+1 opens Accueil, Ctrl+2… the libraries in order (shown in the tooltips); Ctrl+F or / focus the search, also from a detail page, which closes. The account opens a menu (user, server, "non chiffré" over HTTP, "Changer de serveur", "Se déconnecter"). Hidden on detail pages. Under 900 px the search shrinks to its icon and covers the pill while focused; an unfocused query tints the icon.
- Onboarding: server URL, connection test, sign-in, informative errors.
- Home: cinematic hero, Continue Watching, Next Up, Recently Added, Favorites. The heading stays for screen readers only; the hero runs edge to edge under the navigation and the rows scroll to the window edges, aligned on the page margin (`--gutter`, 16 to 80 px). Cards lift on hover and keyboard focus (×1.06, shadow, accent ring), with no scale under reduced motion; white progress bars.
- Home hero (ADR 0015, 0031): above the rows, one title at a time over its backdrop (left and bottom black scrim), 68 % of the window height (420 to 760 px), logo or title (the logo also bounded by the height), episode or year, overview on three lines, "Lecture"/"Reprendre" (white) and "Plus d'infos" (glass), white progress bar when started. Arrows (on hover or focus, hidden under 800 px) and dots only with several titles; ← → inside the hero, the focus follows. No automatic rotation; 200 ms fade, none with reduced motion. Skeleton of the same height while loading, "Réessayer" on error, hidden when empty.
- Home row "Favoris" (ADR 0014): between "À suivre" and "Ajoutés récemment", poster cards of favorite films and series by title; hidden when empty; "Réessayer" on error.
- Films: poster grid, sort/filter/search, skeletons, pagination/virtualization.
- Grid toolbar (ADR 0013): under the library title, native selects "Trier par" (Titre, Date d'ajout, Année, Note), "Ordre" (labels follow the sort: A → Z, Plus récents, Mieux notés…), "Visionnage" (Tous, Non vus, Vus, En cours for films) and "Genre", a "Favoris" toggle (star, `aria-pressed`) and "Réinitialiser" when something differs from the defaults. The count beside the title follows the filters (`aria-live`). No match: "Aucun film ne correspond à ces filtres" with "Réinitialiser les filtres", which keeps the sort and puts the focus back on "Trier par".
- Series: series detail, season switcher, episode cards and progress. In an episode card the thumbnail plays and the title (its hit area covers the text) opens the episode page.
- Episode page (ADR 0016): same layout as a film; above the title the series logo (or name) and "Saison 2 · Épisode 14" in accent caps; air date in the header line; "Réalisation", "Scénario", "Invités"; no favorite toggle; "Épisode précédent" / "Épisode suivant" cards at the bottom (code and title, one column when narrow), crossing seasons. Opened over the series page, which stays mounted and hidden; Escape goes back to the series on that episode, in its season, focused.
- Film detail: backdrop, legible gradient, poster, title, metadata, synopsis, actions and technical info.
- Favorite toggle (ADR 0014), film and series detail: secondary button "Favori" beside "Lecture", outlined star that fills in yellow when pressed (`aria-pressed`, tooltip "Ajouter aux favoris" / "Retirer des favoris"). Changes at once; on failure it goes back and "Favori non modifié" shows at the bottom of the page.
- Player (ADR 0017): full-window over the video. Top: round back button, series kicker in accent caps ("Série · S1 · É12") and title; server decision small at the top right. Bottom, over a dark gradient: seek bar (thicker on hover) between elapsed and remaining time ("−1:41:42"), then ±10 s and a round play/pause on the left with mute and volume, "Audio", "Sous-titres", "Informations de lecture" and fullscreen on the right. Panels open above the bar (radio lists, subtitles start with "Désactivés"). Controls fade after 3 s without input (opacity only, cursor hidden) and come back on mouse or key; never hidden while paused, buffering, scrubbing, over the bar or with a panel open. Keys: Space/K, ←/→, F, M, ↑/↓, I; Escape closes a panel, then leaves fullscreen, then goes back. The volume slider is hidden under 640 px.
- Next episode (ADR 0018): at the natural end of an episode that has a next one, the bottom bar gives way to a card at the bottom right over an opaque background (the top bar stays): "Épisode suivant", series kicker and code, title, "Lecture dans N s" with a gauge, "Lire maintenant" (white, focused) and "Annuler". The next episode starts after 10 s in the same player (fullscreen and volume kept). Escape, "Annuler" or back close the player. The countdown is not announced every second; no gauge transition with reduced motion. A film, the last episode or an early end close the player as before.
- Skip intro and credits (ADR 0030): during a segment, a white button at the bottom right above the controls, still shown when they fade: « Passer l'intro », « Passer le générique », or « Épisode suivant » for credits that run to the end of an episode with a next one; credits followed by a scene add a dark « Épisode suivant » beside it. It never takes the focus; Entrée activates it (except in a text field), its tooltip says so. Hidden with a panel open, on the next-episode card, and in the last half second of the segment. « Passer automatiquement » (Réglages › Lecture) skips each segment once per playback; credits to the end then open the next-episode card. Réglages › Lecture credits IntroDB and TheIntroDB.
- Settings: connection, playback, UI, cache, diagnostics. Implemented: the artwork cache section (size, quota, « Vider le cache des images », ADR 0021); the others come with their behavior.
- Session opening (ADR 0012): brand and "Ouverture de votre session…" while the saved session is validated; never the sign-in form.
- Connection lost ("Serveur injoignable"): a card with the server name, address and user, the error and its hint, "Votre session est conservée", a live status (countdown to the next attempt, check in progress, or attempts suspended) and two actions, "Réessayer" (focused) and "Changer de serveur". At startup it is a full screen; during a session it is a blurred overlay over the app, which stays in place underneath (inert).
- Reauthentication: the sign-in card prefilled with the server and username, focus on the password, "Changer de serveur" as the secondary action. Shown only for a session the server rejected.
- Server form: when sessions were set aside, a "Sessions conservées" list offers "Revenir à {serveur}" without a password.
- Interrupted playback: a banner "Lecture interrompue." with "Reprendre" (focused) and "Fermer", above the page the player was opened from.

## Interaction rules

- Avoid hover-only interactions; keyboard and screen-reader labels required.
- Clearly distinguish resume, play from start and next episode.
- Preserve scroll/focus on back navigation.
- Artwork has placeholders, proper aspect ratios and responsive sizes.
- Never block navigation while fetching metadata; show meaningful skeleton/empty/error states.
- Native video overlays must be tested for fullscreen, window resize, focus and multi-monitor DPI.

## Definition of done for UI

Visually consistent at 100%, 125%, 150% Windows scaling; keyboard usable; no layout overflow in supported window sizes; reduced motion respected; loading and error states demonstrated with screenshots or tests.
