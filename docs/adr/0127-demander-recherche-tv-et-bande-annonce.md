# ADR 0127 — Recherche dans Demander (TV) et bouton « Bande-annonce » des fiches Request

Statut : Accepté (2026-11-20). Complète l'ADR 0125 (intégration Request) ; revient sur la ligne « Bandes-annonces distantes » de l'ADR 0121 pour les fiches de Demander ; réutilise la validation de l'ADR 0083.

## Contexte
- Sur le desktop, l'onglet Demander a un champ de recherche (`SearchList`). Sur la TV, il n'y en a pas : on ne peut chercher un titre à demander que depuis la Recherche générale (rangée « À demander », limitée à ce qu'Emby n'a pas).
- La fiche d'un titre Request (`MediaDetails`) porte `trailerKey` (contrat `contracts/request/openapi.json`, `string | null`). Dans le code du serveur Request (`pickTrailerKey`, `src/server/integrations/tmdb.ts`), c'est la clé d'une vidéo TMDB filtrée sur `site === 'YouTube'` et `type === 'Trailer'` : un identifiant de vidéo YouTube, sans l'adresse autour.
- Le desktop n'a pas d'ouvreur de liens (ni `tauri-plugin-opener`, ni permission shell). La CSP (`default-src 'self'`, pas de `frame-src`) exclut un lecteur YouTube intégré.

## Décision
- **TV, segment « Rechercher »** après « Séries » dans Demander (`SearchRequestsTv.kt`). Il montre le champ de la Recherche générale (`SearchField`, rendu `internal`, avec un texte d'aide à part) puis tous les résultats d'Request pour le terme, ceux déjà dans Emby compris, comme sur le desktop. La grille est celle de Parcourir (`MediaGridTv`, sortie de `BrowseTv`), les pages suivantes sont lues à l'approche de la fin (`LoadAhead`, partagé).
  - Même attente après la frappe que la Recherche générale (`SEARCH_DEBOUNCE_MS`) ; la touche de recherche du clavier cherche tout de suite. Moins de deux lettres : rien ne part.
  - Le terme, les pages lues et le défilement restent dans `RequestsMemory` : Retour depuis un titre retrouve la même grille et la même carte. Une demande faite depuis la fiche relit la recherche.
  - Choisir « Rechercher » donne le focus au champ ; Bas depuis les segments y mène, Haut depuis le champ remonte aux segments. Retour depuis ce segment ramène à Découvrir.
- **Bouton « Bande-annonce »** sur la fiche d'un titre de Demander quand `trailerKey` est présent, après « Lire » et « Demander » (ou « Rien à demander »).
  - La clé n'est acceptée que si elle est un identifiant simple : lettres et chiffres ASCII, `-`, `_`, 64 caractères au plus. L'adresse est construite par l'application : `https://www.youtube.com/watch?v=<clé>`. Rien d'autre n'est ajouté, aucun jeton.
  - **Desktop** : la WebView ne passe que la clé à la commande `ur_open_trailer` (`src-tauri/src/request/trailer.rs`), qui la valide et construit l'adresse en Rust, puis la confie à `explorer.exe` (chemin complet, sans shell), qui ouvre le navigateur par défaut. C'est la fonction déjà utilisée pour ouvrir le dossier des journaux, rendue générique. Échec : message sous les boutons.
  - **TV** : `trailerPage` (`request/Trailer.kt`) valide la clé, construit l'adresse et la repasse par `trailerUrl` (ADR 0083, hôtes YouTube seulement). Elle est ouverte par le même `Intent ACTION_VIEW` que les bandes-annonces Emby (`TrailerHandlers.open`). Aucune application : « Aucune application ne peut ouvrir cette bande-annonce. » Le bouton ne prend le premier focus que s'il est seul.

## Alternatives
- **Lecteur YouTube intégré (iframe)** : il faudrait ouvrir la CSP à YouTube et charger son script dans la WebView. Rejeté.
- **Lecture dans mpv** : il faudrait yt-dlp (ADR 0083). Rejeté.
- **`tauri-plugin-opener`** : une dépendance et une permission IPC de plus, qui accepterait une adresse choisie par la WebView. Une commande à argument validé fait la même chose sans dépendance. Rejeté.
- **Champ toujours visible au-dessus des segments (TV)** : le focus passerait par le champ et ouvrirait le clavier à chaque passage. Un segment ne l'ouvre que si on le choisit. Rejeté.

## Conséquences
- Une bande-annonce qui ne serait pas sur YouTube (le serveur ne les garde pas aujourd'hui) ouvrirait une page YouTube introuvable.
- Le desktop peut maintenant ouvrir une page web, mais seulement une page YouTube construite à partir d'une clé validée.

## Vérification
- Tests : `trailer.rs` (clés acceptées et refusées, commande qui refuse une clé invalide sans rien lancer), `TrailerTest` (Android). Desktop : `npm run verify:web`, `cargo fmt`, `clippy -D warnings`, `cargo test`. Android : `testDebugUnitTest`, `assembleDebug`, `lintDebug`.
- **NON VÉRIFIÉ** : l'ouverture réelle du navigateur sur le desktop, l'application qui ouvre le lien YouTube sur la Shield, et la recherche de Demander sur la Shield (focus, clavier, Retour) contre le vrai serveur Request.
