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
- Films: poster grid, sort/filter/search, skeletons, pagination/virtualization.
- Series: series detail, season switcher, episode cards and progress.
- Film detail: backdrop, legible gradient, poster, title, metadata, synopsis, actions and technical info.
- Player: unobtrusive controls, track selectors, seek bar, volume, fullscreen, keyboard shortcuts, focus handling.
- Settings: connection, playback, UI, cache, diagnostics.

## Interaction rules

- Avoid hover-only interactions; keyboard and screen-reader labels required.
- Clearly distinguish resume, play from start and next episode.
- Preserve scroll/focus on back navigation.
- Artwork has placeholders, proper aspect ratios and responsive sizes.
- Never block navigation while fetching metadata; show meaningful skeleton/empty/error states.
- Native video overlays must be tested for fullscreen, window resize, focus and multi-monitor DPI.

## Definition of done for UI

Visually consistent at 100%, 125%, 150% Windows scaling; keyboard usable; no layout overflow in supported window sizes; reduced motion respected; loading and error states demonstrated with screenshots or tests.
