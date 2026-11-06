# ADR 0022 — Reconnexion du flux et limite d'attente

Statut : Accepté (2026-11-06). Complète l'ADR 0012.

## Contexte
Le scénario `gate4-network` coupe 8 s la connexion de l'application au NAS pendant la lecture d'un film (par le relais local, sans toucher au réseau du PC). Avec le flux curl de mpv, le réseau revient, mais la lecture continue seulement sur ce que mpv avait déjà lu d'avance (≈ 90 s), puis l'application affiche « Lecture interrompue ».

Deux causes successives :
- le flux curl de mpv relance ses essais immédiatement et abandonne dans la même milliseconde : une coupure de quelques secondes suffit à arrêter la lecture une minute plus tard ;
- une fois la reconnexion de FFmpeg activée, c'est l'inverse : FFmpeg recommence ses essais à chaque lecture du démuxeur. Un flux mort n'atteint donc jamais de fin de fichier, et `gate21-session` reste sans « Lecture interrompue » (attente de 90 s dépassée, y compris après un saut hors du cache).

## Décision
**Reconnexion, dans la configuration de mpv.**
- `curl-enabled=no` : le HTTP passe par FFmpeg.
- `stream-lavf-o` :
  - `reconnect=1` : rouvrir le flux s'il se termine avant sa fin annoncée (requête `Range` à la position atteinte) ;
  - `reconnect_streamed=1` : idem pour un flux sans taille connue (transcodage) ;
  - `reconnect_on_network_error=1` : rouvrir aussi après une erreur de connexion (refus, réinitialisation) ;
  - `reconnect_delay_max=7` : FFmpeg attend 0, 1, 3 puis 7 s entre les essais, puis rend la main (le délai suivant, 15 s, dépasse le seuil). mpv lit à nouveau, et le cycle recommence tant que le réseau est coupé.
- `network-timeout=15` : au lieu de 60 s par défaut, pour qu'une connexion qui ne répond plus entre vite dans ce cycle.

Une erreur HTTP (401, 404, 500) n'est pas relancée : `reconnect_on_http_error` reste vide. Un jeton révoqué suit donc toujours le chemin de réauthentification.

**Limite d'attente, dans le moteur Rust** (`StallWatch`, `src-tauri/src/player/mod.rs`, alimenté par le fil d'événements de `MpvEngine`). Si un fichier chargé reste 30 s sans pause de l'utilisateur et sans que sa position bouge, le moteur émet `EndFile { reason: Error }` et demande à mpv d'arrêter ses essais. Cet événement suit le chemin existant de l'ADR 0012 : rapport d'arrêt à la dernière position, « Lecture interrompue », « Reprendre ».

Cette limite est dans le moteur, et non dans `PlayerView.svelte`, pour ne dépendre d'aucune interface. Le critère est l'absence de progression, pas `paused-for-cache` : il couvre aussi un saut qui n'aboutit jamais.

## Alternatives
- **Relancer la lecture côté application** (nouveau `PlaybackInfo` à la position atteinte) : coupure visible, nouvelle session de lecture côté Emby, plus de code. Gardé pour les coupures longues, par « Reprendre ».
- **Essais sans limite** : un NAS éteint laisserait l'utilisateur devant une image figée sans explication. Rejeté.
- **Limite dans `PlayerView.svelte`** : perdue avec l'interface actuelle, et sans effet sur le rapport d'arrêt si la vue n'est pas montée. Rejeté.
- **Compter le temps depuis la coupure** : le lecteur ne voit pas la coupure, seulement ses effets. Tant que le tampon se lit, il n'y a rien à interrompre.

## Conséquences
- Une coupure franche est annoncée après la lecture du tampon, plus 30 s. La durée exacte dépend de ce que mpv a lu d'avance. Pendant un transcodage à 3 Mbit/s, la réserve a atteint 385,7 s et l'annonce 419,5 s (`docs/evidence/gate4-coupure-hls-2026-11-06.md`).
- Tant que le réseau est coupé, le serveur ne reçoit plus de position : « Reprendre » repart de la dernière progression reçue avant la coupure, pas de ce qui a été vu ensuite. Mesuré : 610,0 s enregistrées pour 758,3 s lues. Corrigé au retour du réseau par l'ADR 0024.
- Une image figée plus de 30 s sans aucune progression est traitée comme une interruption, même si le serveur finirait par répondre (transcodage très lent après un saut) ; « Reprendre » reste proposé. Mesuré jusqu'ici : premier segment ≈ 6 s, saut à 10 min en 3,4 s (ADR 0009).
- Un utilisateur en pause n'est jamais interrompu : le décompte repart de la reprise.
- Ces options dépendent de la version de FFmpeg de la libmpv (ADR 0002). Une option inconnue d'une autre build est signalée par mpv dans le journal et ignorée.

## Validation
`StallWatch` a des tests unitaires ; `gate4-network` vérifie la coupure brève, `gate21-session` la coupure longue et `gate4-hls-cut` les deux pendant un transcodage. Résultats : `docs/evidence/gate4-reseau-2026-11-06.md` et `docs/evidence/gate4-coupure-hls-2026-11-06.md`.
