Pourquoi des choix non évidents. Le code fait foi pour le reste.
Ajouter une ligne quand un choix n'est pas évident à la lecture du code ; supprimer la ligne quand le choix change.

## Socle

- **Svelte + Vite sans SvelteKit** — pas de SSR ni de routage serveur à gérer dans une webview locale.
- **Versions exactes et lockfile commité** — builds reproductibles, pas de mise à jour silencieuse d'une dépendance.
- **Tauri 2 stable** — l'API des permissions et des protocoles personnalisés est celle sur laquelle le code s'appuie.
- **Un seul dépôt pour desktop et Android** — contrats Emby, roadmap et choix d'API partagés ; deux dépôts obligeraient à les resynchroniser à la main.

## Lecture et libmpv (Windows)

- **libmpv chargée dynamiquement (libloading)** — une DLL absente produit un diagnostic lisible au lieu d'un échec au démarrage.
- **FFI minimale de 14 fonctions écrite à la main, pas la crate libmpv2** — surface réduite, aucune dépendance de plus à auditer.
- **Build libmpv épinglé, vérifié par SHA-256, `vendor/` ignoré par git** — binaire reproductible et jamais commité.
- **libmpv sous GPL conservée** — usage strictement personnel, aucune redistribution.
- **Options mpv durcies (config=no, load-scripts=no, ytdl=no, osc=no, bindings coupés, hwdec=auto-safe)** — l'app ne doit exécuter aucun script ni config venus du disque ou du réseau.
- **Seuls les chemins locaux absolus et http(s) sont acceptés ; redirections à 0 ; protocoles limités à http, https, tcp, tls ; `tls-verify=yes` explicite** — le moteur ne doit pas être détourné vers un autre schéma ou hôte.
- **Risque accepté : un segment HLS sur un autre hôte reçoit le jeton** — le bloquer casserait des flux légitimes, et l'usage est un LAN personnel.
- **Certificats racine Windows exportés en PEM à chaque chargement HTTPS** — mpv n'utilise pas le magasin système, donc un Emby en HTTPS à certificat d'entreprise échouerait.
- **Vidéo en HWND enfant sous une WebView transparente, chaque vue opaque hors lecture** — seule composition qui garde l'interface au-dessus de la vidéo sans fenêtre flottante.
- **Clavier géré dans la WebView, pas dans mpv** — un seul modèle de focus et de raccourcis pour toute l'app.
- **Reconnexion réseau par FFmpeg (curl désactivé, reconnect_delay_max=7, network-timeout=15)** — les erreurs HTTP ne sont pas rejouées pour qu'un jeton révoqué remonte jusqu'à la réauthentification.
- **Limite d'ouverture de 45 s dans le moteur, `stop` envoyé avant l'événement d'erreur EndFile** — évite un chargement infini et un état incohérent côté interface.
- **`sub-add` asynchrone pour les sous-titres externes** — l'extraction côté serveur peut durer une minute et ne doit pas bloquer la lecture.

## Emby

- **Client Emby en Rust, jeton jamais exposé au JS** — la webview n'a aucune raison de voir le secret.
- **native-tls, pas rustls** — rustls tire aws-lc, qui exige CMake et NASM au build.
- **Jeton dans le Credential Manager, une entrée par serveur et utilisateur ; métadonnées non secrètes et DeviceId stable dans `session.json`** — le secret est isolé, le reste se relit sans coffre.
- **URL de serveur en http/https uniquement, sans identifiants, requête ni fragment** — évite qu'un secret ou un schéma inattendu passe dans les logs ou les en-têtes.
- **PlaybackCoordinator en Rust, ordre Direct Play, Direct Stream, Transcode** — la décision vient du serveur (PlaybackInfo), jamais de l'extension du fichier.
- **URL de flux fournie par le serveur acceptée seulement si relative sous `/videos/`, jeton déplacé dans l'en-tête `X-Emby-Token`** — empêche un serveur ou une réponse piégée d'envoyer le jeton ailleurs.
- **Repli transcodage unique, décidé par le lecteur quand aucune position n'a été vue** — `FileLoaded` seul ne prouve pas que l'image sort.
- **Machine à états du suivi de lecture** — un fichier non chargé n'est jamais annoncé, progression toutes les 10 s, le rapport d'arrêt est rejoué puis abandonné si la session a changé, attente de 3 s à la fermeture.
- **Plafonds de débit par serveur, local ou distant via `ServerUrl::is_local` (pas de sonde)** — une sonde réseau ajouterait de la latence et de la fragilité.
- **`MediaSourceId` dans le corps, `NoCompatibleStream` au lieu de lire une autre version** — ne jamais lire en silence un autre fichier que celui choisi.
- **Qualité changée en cours de lecture seulement sur clic ou OK explicite** — refait PlaybackInfo pour le même `MediaSourceId` et n'envoie `AudioStreamIndex` que pour une piste audio non externe de la source.
- **Segments intro/générique : Emby, puis IntroDB, puis TheIntroDB, seuls les IDs sont envoyés et validés** — le serveur fait foi, les services tiers ne reçoivent rien d'identifiant.
- **Langues : choix local, sinon « Comme le compte Emby » (défaut), sinon règles client sur les préférences du compte, sinon mpv ; rien n'est écrit sur le serveur** — l'app ne modifie pas la configuration du serveur.
- **Bouton « Passer » visible 10 s au début du segment, ensuite seulement avec les commandes** — ne masque pas l'image sans arrêt. Sur TV, `SkipArm` désarme le bouton pour qu'un OK après un seek ne saute pas le segment.
- **Tri et filtres côté serveur avec options typées, choix par bibliothèque en localStorage** — les grandes bibliothèques ne se trient pas côté client.
- **Recherche : requêtes films, séries et épisodes en parallèle, nettoyage en Rust, épisodes filtrés sur début de mot, drapeau « peut-être plus », anti-rebond 250 ms, réponses périmées ignorées** — Emby renvoie du bruit sur les épisodes et la saisie est rapide.
- **Studios et genres filtrés par nom (`Studios=`, `Genres`), pas par ID** — la forme par ID n'est pas documentée ; le nom est validé (non vide, 100 caractères max, ni `|` ni caractère de contrôle).
- **Playlists : ne pas combiner `MediaTypes=Video` et `IncludeItemTypes=Playlist`** — sur Emby 4.10.2.0 cela renvoie toutes les vidéos. Playlists audio filtrées côté client, entrées dédoublonnées à leur première place.
- **Ouverture d'un genre ou studio dans la bibliothèque du titre via `GET /Items/{Id}/Ancestors?UserId=`, repli `sectionLibrary`** — évite de deviner la bibliothèque.
- **Favoris triés par `SortName`** — Emby n'a pas de tri par date d'ajout aux favoris, et un tri stable garde le focus en place.
- **Accueil : les cartes lancent la lecture directement ; « Reprendre » dédoublonné contre « À suivre » ; rangées relues après `playback-stopped` ; héros de 5 éléments max choisi en Rust** — l'accueil reflète toujours l'état réel sans écran intermédiaire.
- **Barre d'accueil : une entrée par bibliothèque films ou séries, uniquement des IDs issus de `/Views`** — jamais d'identifiant déduit.
- **Rangées d'accueil à clés stables (`resume`, `nextUp`, `favorites`, `latest.<libraryId>`, `playlists` sur TV seulement), ordre et visibilité par serveur et utilisateur, stockés localement jamais sur Emby** — clés inconnues ignorées mais conservées, nouvelle bibliothèque affichée après sa précédente.
- **WebSocket Emby : `LibraryChanged` non vide relit l'accueil seulement s'il est affiché, rafales regroupées (relecture 2 s après le dernier message), KeepAlive 30 s, reconnexion 2, 5, 15 puis 30 s** — l'URL à jeton est construite côté natif, jamais loguée, erreurs loguées par catégorie. Le socket reste ouvert toute la session (nécessaire à la lecture liée).
- **États de connexion avec écrans en surimpression qui gardent l'arbre de navigation monté (masqué et inerte)** — on retrouve la position après une coupure. Sur TV : backoff 2, 4, 8, 16, 30, 30 s avec jitter 50-100 %, 6 tentatives automatiques, vérification discrète d'abord, un 401 termine la session.

## Lecture liée PC et TV

- **Relais via Emby** — pas de port ouvert, pas d'appairage, pas d'invite pare-feu.
- **Un seul canal : `POST /Sessions/{Id}/Command` avec `SendString` portant du JSON** — le chemin `/Command/SendString` perd les arguments, et `Playstate` natif ne nomme pas l'émetteur ni ne porte prêt, saut, chapitre ou état horodaté.
- **Lien direct PC-TV non construit** — à reconsidérer seulement si un test réel mesure plus de 150 ms au p95 entre PC et Shield.
- **Le PC mène, la TV suit ; une TV par PC** — évite les conflits de commande et l'arbitrage.
- **Enveloppe versionnée de 1 Ko max avec `from`, `link` et `seq` ; message invalide, étranger ou ancien ignoré sans bruit, seul le `kind` est logué** — le canal est partagé avec d'autres clients Emby.
- **Capacités (`SupportsMediaControl`, `SendString`) déclarées à chaque ouverture du socket** — effet accepté : le tableau de bord Emby montre nos sessions comme contrôlables.
- **Battement chaque seconde, lien perdu après 5 s sans message, chaque lecteur continue seul** — une coupure ne doit jamais bloquer la lecture.
- **Positions absolues, le suiveur corrige de la moitié de l'aller-retour ping/pong, aucune horloge murale comparée** — les horloges PC et Shield ne sont pas synchronisées.
- **Volume jamais envoyé, pistes audio et sous-titres alignées seulement au `load`** — réglages propres à chaque appareil.
- **Vecteurs de test partagés (`docs/sync/vectors.json`) lus par les tests Rust et Kotlin** — garantit que les deux implémentations parlent le même protocole.
- **Limite connue : si Emby redémarre, le lien tombe** — pas de reprise automatique prévue.

## Demander (Request)

- **Client natif par plateforme (reqwest derrière commandes Tauri, OkHttp sur TV), jamais d'appel depuis la webview** — pas de CORS, pas de jeton exposé au JS.
- **Contrat figé dans `contracts/request/openapi.json` avec fixtures partagées validées contre lui** — toute dérive apparaît comme un test de contrat en échec.
- **Échange de jeton depuis l'identité Emby, verrou à vol unique** — le serveur limite à 10 échecs par IP et 5 min, partagés avec la connexion par mot de passe.
- **401 : une réémission puis « rejeté » ; 429 : délai `Retry-After` affiché, aucune nouvelle tentative automatique** — ne pas aggraver le blocage.
- **Jeton dans le coffre de l'OS par compte Emby, lié à l'adresse qui l'a émis, drapeau `signedOut` par compte** — jamais de reconnexion silencieuse après déconnexion volontaire.
- **`http://` accepté seulement sur boucle locale et réseaux privés, avec avertissement** — hors LAN, le jeton circulerait en clair.
- **Protocole d'image `requestimg` avec liste blanche de tailles et de noms de fichiers, jamais de SVG** — pas de contenu actif servi dans la webview.

## Cache et images

- **Protocole `emby-img` : tailles bornées, 6 téléchargements simultanés, 10 Mo max, 401 sans session** — le jeton ne quitte pas le natif et la webview ne peut pas saturer le serveur.
- **Vérification session/serveur avant le cache** — un cache ne doit pas servir une image à un compte déconnecté ou d'un autre serveur.
- **Cache de fichiers sans base de métadonnées (clé = description, mtime = dernier usage)** — le cache est jetable, une base ajouterait de la complexité sans gain.

## Sécurité

- **Jeton utilisateur dans le coffre de l'OS (Credential Manager ; préférences privées Android sans sauvegarde)** — jamais en clair dans SQLite, en git ni en logs.
- **Jeton seulement en en-tête, jamais dans une URL, un log ou l'interface** — une URL est copiée, loguée et mise en cache.
- **TLS toujours validé** — pas de bouton « ignorer le certificat ».
- **Jeton Gitea utilisé seulement pour publier, lu dans `debug.env` (ignoré par git) ou une variable d'environnement** — jamais dans le dépôt.

## UI

- **Tests UI par CDP sur WebView2 (`scripts/e2e/run.ps1`) : fenêtre hors écran, debug uniquement, SWP_NOACTIVATE** — ne vole pas le focus du bureau. Compte de test dans le Credential Manager transmis au seul processus Node, playwright-core épinglé sans téléchargement de navigateur.
- **Barre de titre personnalisée de 32 px (decorations:false), `#app` décalé car la fenêtre est transparente, absente en plein écran** — nécessaire à la composition vidéo transparente.
- **Direction inspirée d'Apple TV : fond noir, matières translucides, boutons primaires blancs, bleu réservé au focus et aux liens, barre de navigation en pilule flottante, focus par élévation, mouvement réduit respecté** — cohérence visuelle voulue, sans copier d'assets propriétaires.
- **Grille de bibliothèque compacte sans barre d'outils, rail de lettres (# et A-Z) à gauche** — visible seulement pour un tri par titre, colonne toujours réservée pour que la grille ne bouge pas. Lettres groupées sur la première lettre sans accent de `SortName` (Œ, Æ, ß, Ø inclus), lettres vides atténuées, toutes accessibles si l'index échoue.
- **Menu de carte (clic droit, touche Menu ou Maj+F10, bouton « ... ») : Lire/Reprendre, playlist, vu, favori, « Retirer de Reprendre » en dernier et seulement sur la rangée Reprendre** — l'action destructrice est isolée au bas du menu.

## Android TV

- **Application Kotlin native dans `android-tv/`, aucun code partagé avec Tauri** — les contrats communs sont l'API Emby et les vecteurs de test, pas du code.
- **Seul cible TV : la Shield** — pas de matrice d'appareils à supporter.
- **`.so` libmpv de la release officielle mpv-android (arm64), SHA-256 vérifié par `scripts/fetch-libmpv-android.ps1`, dossier ignoré** — pont JNI exigeant la classe `is.xyz.mpv.MPVLib`, copiée sous licence MIT et protégée de R8.
- **Compose pour TV ; le lecteur est une Activity séparée avec une SurfaceView hors de Compose** — la vidéo native ne se compose pas proprement dans Compose.
- **Coil sur l'OkHttpClient d'Emby, en-tête d'auth seulement vers l'origine du serveur, aucun jeton dans les URLs d'image ; un seul DiskCache de 256 Mo par processus partagé par tous les ImageLoaders** — deux caches sur le même dossier corrompent le journal.
- **kotlinx.serialization avec `encodeDefaults=true`** — le serveur exige certains champs même à leur valeur par défaut.
- **Machine à états du suivi portée en Kotlin ; rapports émis depuis une coroutine de portée processus** — le rapport d'arrêt survit à `onDestroy`.
- **Rapports d'arrêt non envoyés persistés par compte en préférences privées (`allowBackup=false`, jamais le jeton), 5 max dont un par élément** — pas de perte après un crash, sans croissance non bornée.
- **Touches de télécommande interceptées via `dispatchKeyEvent` de la vue racine, pas de `ComponentActivity`** — la lint RestrictedApi interdit l'autre voie.
- **Choix de pistes par utilisateur sur l'appareil, sans Keystore** — une langue n'est pas un secret. Préférences du compte lues une fois par session sans bloquer, un échec n'est pas mis en cache.
- **Épisode suivant via `AdjacentTo` ; saut alphabétique par une requête d'index de `SortName` plutôt que `NameLessThan` ; tirage au hasard côté serveur (`SortBy=Random`, `Limit=1`)** — `NameLessThan` dépend d'une collation non documentée, et charger toute la liste pour tirer au hasard serait lourd.
- **Sous-titres ASS : `sub-ass-override` à yes/force sur TV, `no` sur Windows** — avec `no`, ils ignorent `sub-pos` et restent sous les commandes (constaté sur Shield) ; différence voulue.
- **Sous-titres externes ajoutés en coroutine sur IO, annulée au chargement du fichier suivant, suivis d'un second choix de piste qui relit les préférences** — l'ajout est lent et ne doit pas bloquer la lecture.
- **Journal verbeux de mpv tenu hors de logcat (lib C et `dlsym`, API 30+)** — logcat saturait et risquait d'exposer des URLs.
- **Pas de vignettes de survol à la recherche dans la vidéo** — le propriétaire garde les vignettes vidéo Emby désactivées (CPU et disque), aucun aperçu n'est attendu.
- **Build debug séparé (`applicationIdSuffix=.debug`, libellé, bandeau, session, réglages et DeviceId Emby propres) ; `PlaybackPocActivity` dans le source set `debug` ; la release est signée avec la clé debug pour s'installer par-dessus un debug** — debug et release ne se mélangent jamais sur l'appareil.
- **Desktop : même séparation en debug (identifiant `io.github.quentinpn.viewer.debug`, service de coffre `Viewer-debug`, config, cache et données WebView2 distincts)** — un debug ne doit pas toucher la session de la release.

## Mises à jour

- **Dépôt public `Viewer-releases` (lecture anonyme) avec manifestes `desktop/latest.json` (format updater Tauri) et `android/latest.json` (versionCode, versionName, notes, url, sha256, taille)** — l'app peut vérifier sans identifiants, alors que le code reste privé.
- **Updater Tauri appelé uniquement depuis Rust, sans permission JS ; installation NSIS silencieuse** — la webview ne déclenche pas d'installation.
- **Clé minisign hors du dépôt, l'app n'accepte que la clé publique embarquée** — seule la signature garantit l'intégrité.
- **`dangerousInsecureTransportProtocol` activé** — le Gitea est en HTTP sur le LAN ; c'est la signature, pas le transport, qui protège. La version du manifeste n'est pas signée : risque accepté sur le LAN.
- **Pastille de mise à jour jamais affichée pendant la lecture ; builds debug vérifient mais ne téléchargent jamais** — pas d'interruption, pas d'écrasement d'un build de travail.
- **TV : SHA-256 de l'APK et comparaison du certificat de signature avant de proposer l'installation (lecture par `GET_SIGNATURES` sous API 33), installation via PackageInstaller** — un APK altéré ou signé par un autre ne s'installe pas.
- **Release Android signée avec le keystore debug de la machine (`~/.android/debug.keystore`)** — le perdre bloque les mises à jour (désinstallation requise), donc le sauvegarder.
- **Pas de mise à jour hors du réseau local (échec silencieux)** — le dépôt de releases n'est joignable que sur le LAN.

## Installateur, release et CI

- **NSIS par utilisateur sans droits admin, `libmpv-2.dll` à côté de l'exe, pas de signature de code** — avertissement SmartScreen accepté, usage personnel.
- **Releases automatiques (Gitea Actions, `release.yml`) : merge commit dans `main` avec Conventional Commits** — les commits `feat` et `fix` publient, `ci`, `docs`, `refactor` et `chore` non. La PR vers `main` lance le mode check ; `workflow_dispatch` en dry-run sur `dev` construit sans publier, à faire avant une release risquée.
- **Action partagée `QuentinPN/claude-boilerplate/release@v2` sur le runner `homeserver` pour version, changelog, tag et back-merge dans `dev`** — logique de release unique pour tous les dépôts.
- **Build et publication sur le runner Windows `viewer-pc`** — la clé de l'updater et le keystore Android n'existent que sur ce PC.
- **Deux pistes : desktop `vX.Y.Z` (tout sauf `android-tv/**`) et Android `android-vX.Y.Z` (seulement `android-tv/**`)** — tags distincts pour que le changelog desktop ignore ceux d'Android. Elles tournent à la suite dans le même job pour qu'Android voie le commit desktop.
- **Publication idempotente, `workflow_dispatch` avec `publish` republie depuis le tag** — une publication interrompue se rattrape sans nouvelle version.
- **`CARGO_TARGET_DIR` conservé entre les builds et LTO fine** — le build est bien plus court, le binaire un peu plus gros.
- **Versions et changelogs jamais édités à la main sauf bump délibéré** — les messages de commit doivent donc rester lisibles.
- **Secret `RELEASE_TOKEN` passé en `GITEA_TOKEN`** — Gitea refuse les secrets nommés `GITEA_*`.
- **Dépôt sur le Gitea du propriétaire (code privé), GitHub gardé comme remote de sauvegarde sans push automatique** — pas de dépendance à un service externe pour livrer.

## Migration Go + Wails v3 (en cours)

- **Partie native portée de Rust/Tauri vers Go + Wails v3 (3.1.0) dans `go-app/` ; `src-tauri/` reste la référence livrée jusqu'à la bascule** — build Rust release de 3 à 5 min à froid (9 min 30 s en CI). La bascule attend le propriétaire, suivi dans `go-app/MAP.md`.
- **Go en `CGO_ENABLED=0`, libmpv chargée par syscall, boucle d'événements en goroutine** — pas de chaîne C à installer.
- **Même composition vidéo que Tauri (fenêtre sans cadre et transparente, HWND principal passé par `wid`, clavier dans la WebView)** — l'interface Svelte ne doit pas changer.
- **Fidélité prouvée par les tests Rust portés et du JSON de référence des commandes et événements** — le port doit répondre exactement comme l'original.
- **Commits `refactor` ou `chore` jusqu'à la bascule (aucune release)** — l'app livrée reste celle de Tauri.
- **Bug Rust trouvé pendant le port : petit et visible par l'utilisateur, corrigé dans Rust tout de suite ; sinon noté dans `go-app/MAP.md`, porté tel quel, corrigé ensuite en Go** — évite de faire diverger les deux bases.
- **Smoke E2E Wails : `VIEWER_E2E_BROWSER_ARGS` lu en debug seulement** — Wails efface `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS`.
- **Conséquences ouvertes : updater ed25519 de Wails contre minisign (une dernière release Tauri ou une réinstallation manuelle), et peut-être une reconnexion unique si Go ne relit pas l'entrée du coffre** — à trancher à la bascule.
