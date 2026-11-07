# ADR 0037 — Débit de lecture

Statut : Accepté (2026-11-07).

## Contexte
Le débit maximal envoyé à `PlaybackInfo` (`MaxStreamingBitrate`, dans la requête et dans le profil d'appareil) vaut 400 Mbit/s, fixé dans le code (ADR 0009). Seule la variable de build debug `VIEWER_E2E_MAX_BITRATE` le baisse, pour forcer un transcodage dans les scénarios. `PRODUCT.md` demande en P1 une préférence de débit pour l'accès distant : hors du réseau local, la liaison montante du mini-PC ou une connexion mobile ne suit pas un fichier à 20 Mbit/s, et c'est au serveur de transcoder.

Ce que le serveur fait d'un plafond est déjà observé (ADR 0009, Emby 4.10.1.0) : au-dessus, `SupportsDirectPlay` et `SupportsDirectStream` passent à faux, et la `TranscodingUrl` porte `TranscodeReasons=ContainerBitrateExceedsLimit`, affiché « Transcodage · débit trop élevé ».

## Décision
- Réglages › « Qualité de lecture », deux listes : « Réseau local » et « Accès distant ». Choix : « Maximale (automatique) », 40, 20, 10, 8, 4, 3 et 1,5 Mbit/s. « Maximale » envoie les 400 Mbit/s d'avant.
- **Local ou distant, d'après l'adresse du serveur** utilisée par la session : la règle qui autorise déjà le HTTP en clair (`ServerUrl::is_local` : boucle locale, adresses privées et lien-local, suffixes `.local`, `.lan`, `.home.arpa`, `localhost`). Pas de sonde réseau ni de mesure de débit.
- Stockage local, **par serveur** (identifiant Emby du serveur, pas l'adresse ni l'utilisateur), dans `playback-quality.json` à côté de `session.json`. Aucun secret. Un fichier illisible ou une valeur hors liste vaut « Maximale ». Les deux sur « Maximale » retire l'entrée du serveur.
- Côté Rust, `emby::quality` porte la liste des choix et la validation. Commandes typées : `emby_playback_quality` (les deux plafonds du serveur courant, la liste des choix et si l'adresse est locale) et `emby_set_playback_quality(local, remote)`, qui refuse toute valeur hors liste. L'interface ne fabrique aucune requête : `EmbyService` choisit le plafond à chaque négociation et le met dans la requête et le profil d'appareil.
- Ordre de priorité : la variable `VIEWER_E2E_MAX_BITRATE` (build debug seulement, ignorée en release comme avant), puis le réglage du serveur pour le type d'adresse, puis 400 Mbit/s.
- Le plafond appliqué part avec `PlaybackStart` (`bitrateCap`, `remoteAddress`) et s'affiche dans « Informations de lecture » : « Débit maximal · 4 Mbit/s · accès distant » ou « Automatique · réseau local ».
- Le réglage vaut pour la prochaine négociation : une lecture en cours n'est pas renégociée. Le passage à l'épisode suivant et le repli en transcodage (ADR 0023) négocient de nouveau, donc suivent le réglage.

## Alternatives
- **Un seul plafond** : plus simple, mais l'utilisateur qui veut limiter à distance perdrait la lecture directe chez lui. Écarté, puisque la distinction par adresse ne coûte rien.
- **Détecter le réseau réel** (sonde de débit, comparaison avec le sous-réseau du PC) : coûteux et fragile (VPN, NAT en épingle, DNS interne). Écarté.
- **Plafond par adresse plutôt que par serveur** : un même serveur joint par deux adresses aurait deux jeux de réglages. Le type d'adresse suffit à faire la différence. Écarté.
- **`localStorage` de la WebView, passé à `emby_play`** : la WebView ferait une partie de la négociation, et tous les appels de lecture devraient le transmettre. Écarté.
- **Plafond automatique selon le débit mesuré** : demande une mesure fiable et un retour du lecteur. Reporté.

## Conséquences
- Un serveur joint à distance par un nom DNS public reste « distant » même depuis la maison (DNS interne, NAT en épingle). Pour lire à pleine qualité chez soi, il faut une session sur l'adresse locale. Les deux sessions partagent les mêmes réglages, puisqu'elles visent le même serveur.
- Une adresse privée jointe par VPN compte comme locale.
- Le plafond ne touche que le choix du serveur : un fichier sous le plafond reste en lecture directe, quelle que soit la liaison.
- Les débits retenus sont ceux d'Emby : le transcodeur vise ce plafond, sans garantie sur le débit réel.
- `gate5-release` reste valable : le build release ignore la variable, et le réglage par défaut est « Maximale ».

## Validation
- `cargo test quality` : validation des choix, choix local ou distant, fichier illisible, valeurs hors liste ignorées, écriture atomique.
- `cargo test playback_quality` (serveur Emby simulé) : le plafond local arrive dans `MaxStreamingBitrate` et le profil, le plafond distant ne s'applique pas à une adresse locale, le réglage survit à un redémarrage et reste propre à chaque serveur, une valeur hors liste est refusée, la variable debug passe devant.
- Vitest : libellés et choix (`src/lib/settings/quality.test.ts`), ligne du panneau d'informations (`src/lib/player/format.test.ts`).
- `./scripts/e2e/run.ps1 gate3-quality` sur le serveur réel : réglage à 1,5 Mbit/s par le clavier dans Réglages, gardé après un redémarrage, lecture courte transcodée avec « Transcodage · débit trop élevé » et « Débit maximal · 1,5 Mbit/s · réseau local », puis retour à « Maximale » et lecture directe.
