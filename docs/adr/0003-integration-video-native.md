# ADR 0003 — Intégration de la vidéo native sous la WebView

Statut : Accepté, sur la base du spike Gate 1 du 2026-10-27. Périmètre validé limité (voir « Restant à vérifier »).

## Contexte
ARCHITECTURE.md identifie la composition WebView2 + libmpv comme le risque de faisabilité principal. Il fallait afficher la vidéo rendue par mpv (D3D11, décodage matériel) **sous** une interface HTML (contrôles, badges) sans faire transiter les images par JavaScript.

## Options testées
Trois stratégies, implémentées dans `src-tauri/src/video_surface.rs` et sélectionnables par `VIEWER_VIDEO_MODE`. Toutes utilisent une fenêtre Tauri transparente (`transparent: true`, fond `#00000000`) avec une page HTML au fond transparent.

| Mode | Principe | Résultat observé |
|---|---|---|
| `child` | Fenêtre Win32 enfant créée en bas du z-order, passée à mpv via `wid` | **Échec visuel.** mpv rend (osd = taille fenêtre), mais rien n'est visible : on voit le bureau à travers la fenêtre. |
| `parent` | HWND de la fenêtre principale passé directement à mpv via `wid` (mpv crée son propre enfant) | **Succès.** Vidéo visible, overlays HTML semi-transparents composés par-dessus, plein écran et redimensionnement suivis. |
| `separate` | Pas de `wid`, mpv ouvre sa propre fenêtre | Lecture OK, mais la vidéo ne suit ni le plein écran ni le redimensionnement (osd figé en 1920×1080). Pas d'overlay possible. |

Le mode `child` est un piège notable : **tous ses contrôles automatiques passaient** (taille de rendu, hwdec, seek…). Seules les captures d'écran ont révélé l'échec. Le scénario Gate 1 impose donc une revue visuelle des captures.

## Décision
- Mode **`parent`** par défaut.
- La page reste transparente pendant la lecture. Hors lecture, chaque vue applique son propre fond opaque : sinon le bureau est visible à travers la fenêtre.
- Les commandes IPC sont `async`, pour ne pas s'exécuter sur le thread UI. Le teardown de mpv (`mpv_terminate_destroy`) a lieu hors du thread UI, puisque la fenêtre vidéo embarquée a pour parent la fenêtre principale. Fermer la fenêtre pendant la lecture déclenche d'abord un teardown en tâche de fond, puis `app.exit(0)`.
- Après chaque teardown, Rust émet `PlayerEvent::Shutdown`, quelle que soit l'origine de la fermeture. Bug trouvé par capture : l'UI gardait les contrôles après une fermeture initiée côté Rust.
- Le clavier reste dans la WebView : `input-vo-keyboard=no` et `input-default-bindings=no` côté mpv, raccourcis gérés en TypeScript (`mapPlayerKey`).

## Alternatives non testées (repli si `parent` régresse)
- Deux fenêtres de premier niveau synchronisées : vidéo dessous, WebView transparente « owned » dessus.
- Rendu mpv via l'API `render` (D3D11/OpenGL) dans une surface DirectComposition partagée avec WebView2 en mode composition. Plus robuste, mais demande un accès WebView2 que Tauri n'expose pas directement.

## Conséquences et risques
- La composition repose sur un comportement de WebView2 et de DWM non documenté comme contrat. Il faut rejouer `scripts/spike/run-gate1.ps1` à chaque mise à jour de Tauri, WebView2, libmpv ou pilote.
- La fenêtre transparente rend visible le bureau sous toute zone HTML transparente quand aucune vidéo n'est active. L'UI doit fournir un fond opaque hors lecture (règle à intégrer dans le skill desktop-ui).

## Validation
Voir `docs/evidence/gate1-2026-10-27.md` pour l'environnement, les commandes, les résultats et ce qui reste à vérifier.
