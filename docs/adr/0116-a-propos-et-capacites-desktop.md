# ADR 0116 — À propos, serveur et capacités du PC dans les Réglages desktop

Statut : Accepté (2026-11-13). Rejoint sur Windows l'écran « À propos » de l'Android TV (ADR 0076).

## Contexte
Les Réglages de l'Android TV montrent la version, le type de build, l'appareil, le serveur et ce que l'appareil sait décoder. Le desktop n'affichait que sa version. Pour comprendre un souci de lecture, le propriétaire veut voir les mêmes informations sur le PC.

## Décision
- **Serveur** : nom, version d'Emby et adresse, relus à l'ouverture des Réglages avec `/System/Info/Public` de la session ouverte (commande `emby_server_info`). Sans réponse : « Serveur injoignable » et un bouton « Réessayer ». Le jeton reste dans l'en-tête, jamais dans l'adresse ni le journal.
- **À propos** : version, compilation (« Débogage » pour `debug_assertions`, sinon « Publication »), Windows (édition, version et build lus dans `HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion`) et chaque carte graphique (description DXGI et version du pilote `UMDVersion`). Le registre dit encore « Windows 10 » sur Windows 11 : le build 22000 ou plus le corrige.
- Une seule commande typée en lecture seule, `system_report`, sans shell ni WMI. Elle dépend du crate `windows` 0.62, déjà présent dans le lockfile par Tauri, avec seulement les fonctions DXGI utiles.
- **Capacités de lecture** : pour la première carte graphique matérielle (celle que D3D11, donc le `hwdec=auto-safe` de mpv avec `d3d11va`, prend par défaut), les profils de décodage D3D11 Video que le pilote liste (`GetVideoDecoderProfile`) : H.264, HEVC, HEVC 10 bits, VP9, VP9 10 bits (profil 2), AV1. Listé : « Matériel » ; absent : « Logiciel » (mpv décode quand même, sur le processeur). Pour chaque écran, HDR « Activé » quand l'espace colorimétrique de la sortie DXGI est BT.2020 PQ, sinon « Désactivé dans Windows » : on ne sait pas dire si l'écran le gère quand Windows ne l'active pas. Dolby Vision : pas de détection possible, une note dit que la lecture n'est pas certifiée et que mpv lit la couche de base.
- Limites connues : un profil listé ne garantit pas toutes les résolutions ni tous les niveaux (`CheckVideoDecoderFormat` n'est pas appelé) ; une deuxième carte graphique n'est pas sondée ; le passthrough audio n'est pas décrit.
- Ce qui ne peut pas être lu s'affiche « Inconnu », jamais deviné.

## Conséquences
- Rien n'est envoyé : les informations restent à l'écran.
- La version Windows dépend de valeurs du registre qui peuvent manquer (`DisplayVersion` avant 20H2) : elle est alors omise.
