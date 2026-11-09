# ADR 0051 — Réglages (Android TV)

Statut : Accepté (2026-11-09). Reprend une partie de `Settings.svelte` et `PlaybackQuality.svelte` (Windows, ADR 0030 et 0037).

## Contexte
Sur TV, les sources externes d'intros (IntroDB, TheIntroDB) étaient toujours interrogées, rien ne se passait seul, et `MaxStreamingBitrate` valait toujours 400 Mbit/s. Windows permet de régler ces trois points.

## Décision
- **Écran « Réglages »** : bouton de l'en-tête de l'accueil, à côté de « Rechercher ». Retour ramène le focus sur ce bouton. Une ligne par réglage (`ListItem`), OK change la valeur, enregistrée aussitôt.
- **Intros et génériques** : « Chercher les intros et génériques sur IntroDB et TheIntroDB » (activé par défaut) et « Passer automatiquement » (désactivé par défaut), mêmes libellés et mêmes valeurs par défaut que Windows (`src/lib/player/segments.ts`). Désactivées, les sources externes ne sont plus appelées : seuls les repères d'Emby restent.
- **Passage automatique** : chaque intro et chaque générique est passé une fois par lecture ; revenir en arrière affiche de nouveau le bouton. Le générique d'un épisode suivi d'un autre garde la carte « Épisode suivant », qui tient lieu de passage ; une fois la carte fermée, le générique n'est pas sauté dans le dos de l'utilisateur. Rien n'est sauté pendant que le menu des pistes est ouvert.
- **Qualité de lecture** : deux plafonds, « Réseau local » et « Accès distant », choisis parmi les débits de Windows (`BITRATE_CHOICES` de `src-tauri/src/emby/quality.rs` : 40, 20, 10, 8, 4, 3 et 1,5 Mbit/s, ou « Maximale (automatique) »). OK passe au débit suivant. Le plafond suit l'adresse de la session (`ServerUrl.isLocal`, mêmes règles que `is_local` sous Windows) et part dans `MaxStreamingBitrate` de PlaybackInfo ; sans plafond, la limite par défaut (400 Mbit/s).
- **À propos** : numéro de version de l'application.
- **Stockage** : `SharedPreferences` « settings », par utilisateur Emby, comme les filtres de bibliothèque. Aucun secret, rien n'est écrit sur le serveur. Une valeur hors liste (ancienne version, fichier modifié) est lue comme « Maximale ». Les réglages sont relus à chaque élément lu.

## Alternatives
- **Plafond par serveur comme sous Windows** : le compte Emby identifie déjà un serveur sur TV, où une seule session est ouverte à la fois ; la clé par utilisateur suffit.
- **Liste déroulante pour le débit** : un panneau de plus à la télécommande pour huit valeurs ; le pas à pas, comme le tri de la bibliothèque, est plus court.

## Écarté de ce lot
- **Langues audio et sous-titres par défaut** : la TV retient déjà la dernière piste choisie (ADR 0042) ; mêler les deux modèles demande une décision à part.
- **Apparence des sous-titres** (taille, couleur, fond, position) : s'applique au lecteur TV, mais demande de nouvelles propriétés mpv et un essai sur le Shield ; prochain lot possible.
- **Cache des images et diagnostics** : propres au poste Windows (cache SQLite, journaux) ; Coil gère son cache sur TV.

## Conséquences
- Un plafond bas force le transcodage côté Emby : à vérifier sur le Shield (méthode « Transcode » dans le journal `ViewerTV`).
