# ADR 0006 — Tests d'interface pilotés par le protocole DevTools de WebView2

Statut : Accepté (2026-10-28).

## Contexte
Les scénarios E2E de la Gate 2 (`scripts/gate2/*.ps1`) pilotent l'app avec de vraies frappes (`SendKeys`), le curseur et la molette du système, et des captures de l'écran. Ils exigent que Viewer soit la fenêtre au premier plan pendant toute la durée du test : l'utilisateur ne peut pas se servir de son PC en même temps. Il a demandé que les tests ne monopolisent plus son poste.

Contraintes :
- tester la vraie app (build de debug, vrai WebView2, vraies commandes Tauri, vrai serveur Emby), pas une page Vite isolée ;
- aucun secret dans les arguments, les fichiers ou l'environnement du processus de l'app ;
- rien de tout cela dans un build de release.

## Décision
- Le scénario lance le build de debug avec `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=9339` et s'y attache avec `playwright-core` (`chromium.connectOverCDP`). Les clics, les frappes, la molette et les captures passent par le protocole DevTools : ils visent la page, pas le système.
- Variable `VIEWER_E2E_OFFSCREEN`, lue **uniquement dans les builds de debug** : la fenêtre principale s'ouvre hors de l'écran (-20000, -20000) avec `focus: false`. Elle n'est jamais activée et ne couvre pas le bureau. Chromium reçoit `--disable-features=CalculateNativeWinOcclusion`, `--disable-backgrounding-occluded-windows` et `--disable-renderer-backgrounding` pour continuer à rendre la fenêtre invisible.
- Le redimensionnement passe par `SetWindowPos` avec `SWP_NOACTIVATE` : c'est la vraie fenêtre qui change de taille, sans émulation de viewport.
- Le compte Emby de test est lu dans le Gestionnaire d'identification Windows (identifiant générique `viewer-e2e-emby`, créé par l'utilisateur avec `cmdkey`). `scripts/e2e/run.ps1` le passe au seul processus Node du scénario, qui le retire de l'environnement de l'app.
- `playwright-core` 1.64.1 entre en dépendance de développement, version exacte. Il ne télécharge aucun navigateur et n'est pas embarqué dans l'app.

## Alternatives
- **Garder `SendKeys` et les captures d'écran** : c'est le fonctionnement actuel, qui bloque le poste. Écarté pour les scénarios d'UI.
- **`tauri-driver` + WebDriver (msedgedriver)** : solution officielle de Tauri, mais elle exige un msedgedriver à la version exacte du runtime WebView2 et ne règle ni le premier plan ni la position de la fenêtre. Plus lourd pour le même résultat.
- **Page Vite dans un navigateur, commandes Tauri simulées** : rapide, mais ne teste ni Rust, ni le protocole `emby-img`, ni WebView2. Reste possible plus tard pour des tests de composants.
- **Machine virtuelle ou second bureau Windows** : isolement complet, mais coûteux à maintenir et sans le GPU réel.

## Conséquences
- Les scénarios d'UI tournent pendant que l'utilisateur travaille. Ils ne consomment que du CPU et du réseau.
- Les captures CDP ne montrent que le contenu HTML. La vidéo native de mpv, dessinée sous le WebView (ADR 0003), n'y apparaît pas. **Les contrôles visuels de lecture (Gate 1) restent manuels ou passent par une capture d'écran réelle, avec l'accord de l'utilisateur.**
- Les frappes CDP ne passent pas par la file de messages Windows : les raccourcis interceptés par le système ou par la fenêtre native (mpv au premier plan, touches globales) ne sont pas couverts. Les scénarios Gate 1 gardent les vraies frappes.
- Pendant un test, le port DevTools est ouvert sur 127.0.0.1. Tout processus local peut alors piloter la page. Il n'est ouvert que par les scripts de test, jamais par l'app elle-même.
- Les anciens scripts `scripts/gate2/*.ps1` sont retirés après validation du scénario CDP sur le serveur réel (ils restent dans l'historique git).

## Validation
- 2026-10-28 : sonde `launch()` sur l'écran de connexion. Attache CDP en ~0,4 s, page `visible` en 1280×720, capture correcte. La fenêtre au premier plan reste celle de l'utilisateur (Firefox) pendant le test. Fermeture par WM_CLOSE : code de sortie 0.
- 2026-10-28 : scénario complet `scripts/e2e/gate2-ui.mjs` sur le serveur réel (run `gate2-ui-20261028-083506`) : 15/15 contrôles réussis, captures revues, coffre Windows vide à la fin. Le poste est resté utilisable pendant tout le run.
