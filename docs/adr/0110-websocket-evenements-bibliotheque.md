# ADR 0110 — Ajouts sur le serveur poussés par le WebSocket Emby

Statut : Accepté (2026-11-13). Complète l'ADR 0109.

## Contexte
Après l'ADR 0109 (relecture toutes les 5 min), le propriétaire précise : « lorsque j'attends une série, je suis sur la page d'accueil et j'attends qu'elle apparaisse à l'écran […] lorsque j'ai l'appli d'ouverte je veux que ça soit actualisé direct ». Emby pousse les changements de bibliothèque (`LibraryChanged`) sur sa socket d'événements.

## Constat sur le serveur (Emby 4.10.2.0, compte démo, 2026-11-13)
- `ws://<serveur>/embywebsocket?api_key=<jeton>&deviceId=<appareil>` : socket rattachée à la session ; un favori ajouté puis retiré arrive en `UserDataChanged` moins d'une seconde après.
- Même URL sans `api_key`, jeton en `X-Emby-Token` / `X-Emby-Authorization` : la socket s'ouvre mais **ne reçoit rien**. Le jeton dans l'URL est donc la seule voie supportée.
- Aucun `ForceKeepAlive` reçu en 75 s.

## Décision
- Une socket par app, **ouverte seulement pendant que l'accueil est affiché** (desktop : montage de `Home.svelte` ; Android TV : accueil composé et activité `STARTED`), fermée sinon.
- Seul `LibraryChanged` avec `ItemsAdded`, `ItemsUpdated` ou `ItemsRemoved` non vide compte. Une rafale (scan, affiches qui arrivent après) est regroupée : relecture en place **2 s après le dernier message**.
- `{"MessageType":"KeepAlive"}` toutes les 30 s ; reconnexion après 2, 5, 15 puis 30 s.
- **Jeton** : l'URL est construite en Rust (`EmbyClient::library_socket_url`) ou dans `EmbyClient.openEventSocket` (Kotlin), passée directement à la bibliothèque WebSocket, jamais renvoyée à l'UI ni journalisée ; les erreurs ne sont journalisées que par catégorie.
- La relecture de l'ADR 0109 (5 min, retour dans l'app) reste en secours quand la socket est coupée.
- Dépendances desktop ajoutées : `tokio-tungstenite` (native-tls, comme `reqwest`) et `futures-util`. Android : OkHttp, déjà présent.

## Alternatives
- **Socket dans la WebView** : l'UI aurait le jeton et l'URL brute, contraire à ARCHITECTURE.md. Écarté.
- **Socket ouverte toute la session** : utile aussi pour d'autres événements, mais rien d'autre ne l'utilise aujourd'hui. Écarté pour cette tranche.
- **Réagir aussi à `UserDataChanged`** (vu sur un autre appareil) : les actions de l'app elle-même en produisent déjà ; hors de cette tranche.

## Conséquences
- Un titre ajouté apparaît environ 2 s après qu'Emby l'a enregistré, dès lors que l'accueil est affiché.
- Le jeton passe dans l'URL de la socket : acceptable en réseau local ; en https il reste chiffré. Il peut apparaître dans les journaux d'accès d'un proxy inverse.
- Une mise à jour de métadonnées pendant un scan relit aussi l'accueil (regroupée).

## Vérification
Voir ROADMAP.md (entrée ADR 0110).

## Amendement (2026-11-14, ADR 0124)
La socket reste ouverte toute la session connectée (sur la TV : tant que l'app est au premier plan), plus seulement sur l'accueil, pour porter la lecture liée. À chaque connexion, l'app déclare `Sessions/Capabilities/Full`. `LibraryChanged` ne relit toujours l'accueil que lorsqu'il est affiché.
