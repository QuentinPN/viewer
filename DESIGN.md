# Viewer — Design and UX specification

## Experience

Premium cinematic desktop media browser inspired by Infuse's clarity. Original Viewer identity, dark theme, subtle depth, generous media imagery, restrained animation. French copy.

## Design tokens (initial proposals; validate in UI)

- Background `#0B0D11`; surface `#151922`; elevated `#202631`.
- Text primary `#F5F6F8`, secondary `#A6AFBC`, muted `#738092`.
- Accent `#55B6E8`; success `#64C69A`; danger `#F07D82`.
- Radius: 8/12/18 px; spacing: 4 px scale; motion: 120–240 ms with reduced-motion support.
- Use system-friendly typography with reliable Windows rendering; avoid bundling unlicensed fonts.

## Main screens

- Onboarding: server URL, connection test, sign-in, informative errors.
- Home: cinematic hero, Continue Watching, Next Up, Recently Added, Favorites.
- Home hero (ADR 0015): above the rows, one title at a time over its backdrop (left and bottom scrim), logo or title, episode or year, overview on three lines, "Lecture"/"Reprendre" (white) and "Plus d'infos" (translucent), progress bar when started. Arrows (on hover or focus) and dots only with several titles; ← → inside the hero, the focus follows. No automatic rotation; 200 ms fade, none with reduced motion. Skeleton of the same height while loading, "Réessayer" on error, hidden when empty.
- Home row "Favoris" (ADR 0014): between "À suivre" and "Ajoutés récemment", poster cards of favorite films and series by title; hidden when empty; "Réessayer" on error.
- Films: poster grid, sort/filter/search, skeletons, pagination/virtualization.
- Grid toolbar (ADR 0013): under the library title, native selects "Trier par" (Titre, Date d'ajout, Année, Note), "Ordre" (labels follow the sort: A → Z, Plus récents, Mieux notés…), "Visionnage" (Tous, Non vus, Vus, En cours for films) and "Genre", a "Favoris" toggle (star, `aria-pressed`) and "Réinitialiser" when something differs from the defaults. The count beside the title follows the filters (`aria-live`). No match: "Aucun film ne correspond à ces filtres" with "Réinitialiser les filtres", which keeps the sort and puts the focus back on "Trier par".
- Series: series detail, season switcher, episode cards and progress. In an episode card the thumbnail plays and the title (its hit area covers the text) opens the episode page.
- Episode page (ADR 0016): same layout as a film; above the title the series logo (or name) and "Saison 2 · Épisode 14" in accent caps; air date in the header line; "Réalisation", "Scénario", "Invités"; no favorite toggle; "Épisode précédent" / "Épisode suivant" cards at the bottom (code and title, one column when narrow), crossing seasons. Opened over the series page, which stays mounted and hidden; Escape goes back to the series on that episode, in its season, focused.
- Film detail: backdrop, legible gradient, poster, title, metadata, synopsis, actions and technical info.
- Favorite toggle (ADR 0014), film and series detail: secondary button "Favori" beside "Lecture", outlined star that fills in yellow when pressed (`aria-pressed`, tooltip "Ajouter aux favoris" / "Retirer des favoris"). Changes at once; on failure it goes back and "Favori non modifié" shows at the bottom of the page.
- Player: unobtrusive controls, track selectors, seek bar, volume, fullscreen, keyboard shortcuts, focus handling.
- Settings: connection, playback, UI, cache, diagnostics.
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
