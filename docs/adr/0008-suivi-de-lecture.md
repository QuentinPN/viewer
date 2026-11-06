# ADR 0008 — Suivi de lecture et reprise

Statut : Accepté (2026-10-28).

## Contexte
PRODUCT.md demande de reprendre une lecture et d'envoyer au serveur le début, la progression et l'arrêt, avec Emby comme seule source de vérité pour la position et le statut « vu ». L'ADR 0007 a reporté le transcodage tant que l'arrêt n'était pas signalé (l'encodage du NAS doit être arrêté).

Contrat lu dans `/emby/openapi.json` (Emby 4.10.1.0) :
- `POST /Sessions/Playing` (`PlaybackStartInfo`), `POST /Sessions/Playing/Progress` (`PlaybackProgressInfo`), `POST /Sessions/Playing/Stopped` (`PlaybackStopInfo`) ;
- champs utilisés : `ItemId`, `MediaSourceId`, `PlaySessionId`, `PositionTicks`, `RunTimeTicks`, `IsPaused`, `CanSeek`, `PlayMethod`, `EventName` (`TimeUpdate`, `Pause`, `Unpause`…), `Failed` ;
- les variantes `/Users/{UserId}/PlayingItems/…` et `/Sessions/Playing/Ping` existent aussi ; on garde les trois routes `Sessions`, que le client web d'Emby utilise et qui portent le `PlaySessionId`.

## Décision
- Le suivi vit en Rust (`src-tauri/src/emby/reporting.rs`), comme la négociation. L'UI n'envoie aucun rapport et n'a pas accès aux identifiants de session de lecture.
- Un automate pur (`Tracker`) transforme les événements de `PlayerEngine` en rapports :
  - `emby_play` annonce la lecture (`begin`) avant de charger le fichier ;
  - `FileLoaded` → début, à la position de départ ;
  - pause / reprise → progression `Pause` / `Unpause` immédiate ;
  - fin de saut → progression `TimeUpdate` dès la position suivante ;
  - toutes les 10 s, lecture ou pause → progression `TimeUpdate`, qui garde aussi la session vivante ;
  - fin de fichier, arrêt, fermeture du lecteur → arrêt, avec `Failed` si mpv signale une erreur.
  Un fichier qui échoue avant `FileLoaded` n'est jamais annoncé.
- Une tâche asynchrone reçoit ces événements dans une file, envoie les rapports dans l'ordre et réessaie une fois l'arrêt (1 s). Après l'arrêt, elle relit l'état de l'élément (`/Users/{Id}/Items/{Id}`) et l'envoie à l'UI (`emby://playback-stopped` : id, vu, point de reprise). L'UI met la fiche ou l'épisode à jour sans recharger la liste.
- Fermeture de la fenêtre pendant la lecture : après l'arrêt de mpv, on attend que l'arrêt soit envoyé (3 s au plus) avant de détruire la fenêtre.
- Reprise : `emby_play(itemId, resume)`. Avec `resume`, Rust relit la position du serveur au moment de lancer, pas une valeur transmise par l'UI. Une position au-delà de la durée est ignorée. La fiche d'un film propose « Reprendre » et « Lire depuis le début » ; un épisode commencé reprend.
- Le statut « vu » n'est jamais écrit directement : le serveur le décide à l'arrêt selon ses propres seuils.
- Pistes : les indices de pistes de mpv ne correspondent pas aux `Index` des `MediaStreams` d'Emby. `AudioStreamIndex` et `SubtitleStreamIndex` ne sont pas envoyés.

## Alternatives
- **Rapports envoyés par le frontend** : l'UI aurait besoin du `PlaySessionId` et ne survit pas à la fermeture de la fenêtre. Écarté.
- **Routes `/Users/{UserId}/PlayingItems/…`** : paramètres en requête, sans corps ; équivalentes, mais moins répandues côté clients officiels. Écarté.
- **Position de reprise fournie par l'UI** : risque d'une valeur périmée après une autre lecture sur un autre appareil. Écarté.

## Conséquences
- Un processus tué n'envoie pas d'arrêt : le serveur garde la dernière progression et ferme lui-même la session. Après une coupure, le rapport d'arrêt est renvoyé au retour du réseau (ADR 0024).
- La fermeture de la fenêtre pendant une lecture peut prendre jusqu'à 3 s de plus si le serveur ne répond pas.
- Le transcodage (ADR 0007) peut maintenant s'appuyer sur l'arrêt pour libérer l'encodage du NAS.
