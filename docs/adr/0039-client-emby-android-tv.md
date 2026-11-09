# ADR 0039 — Client Emby Android TV : Compose for TV, Coil, jeton avant l'init de mpv

Statut : Accepté (2026-11-09). Première tranche du client sur la Shield.

## Contexte
Après la Gate TV-1 (ADR 0038), l'app Android TV doit se connecter à Emby, afficher l'accueil et lire un élément en négociant le flux par PlaybackInfo. Plusieurs choix d'outils et un problème de fuite du jeton se sont posés.

## Décision
- **UI : Jetpack Compose for TV** (`androidx.tv:tv-material` 1.1.0, BOM Compose 2026.09.00). Les `Card` gèrent le focus, la mise à l'échelle et la bordure à la télécommande. Le lecteur reste une `Activity` avec une `SurfaceView`, hors Compose, comme dans le PoC.
- **Images : Coil 3** (`coil-compose` + `coil-network-okhttp`) sur le même `OkHttpClient` que le client Emby. Un intercepteur ajoute `X-Emby-Authorization` aux seules requêtes vers l'origine du serveur. L'URL des images ne porte jamais de jeton.
- **Client Emby** : OkHttp 5 + kotlinx.serialization (`encodeDefaults = true`, sinon les drapeaux par défaut du profil ne partent pas). Mêmes contrats et même profil d'appareil que le client Windows (ADR 0004, 0007 à 0009). Redirections refusées, URL de flux acceptées seulement sur `/videos/` de la même origine, paramètres de jeton retirés.
- **Jeton** : chiffré en AES-256-GCM par une clé de l'Android Keystore (alias `viewer.session`), stocké dans des `SharedPreferences` privées. Une clé illisible efface la session au lieu de planter.
- **HTTP en clair** : `network_security_config.xml` l'autorise au niveau de la plateforme, car le serveur du foyer est en `http://192.0.2.14:8096`. La vraie barrière est `ServerUrl` : HTTP n'est accepté que pour une adresse locale (privée, loopback, `.local`).
- **En-têtes de flux posés avant `mpv_initialize`** : `libplayer.so` (mpv-android) abonne libmpv aux journaux de niveau `v` et les recopie lui-même dans logcat (balise `mpv`), avant tout filtrage côté Kotlin. Après l'init, libmpv journalise la valeur de chaque propriété, de chaque commande `set` et des options par fichier de `loadfile`. Seules les options posées avant l'init ne sont pas journalisées. Le `MpvPlayerEngine` reçoit donc ses en-têtes à la construction et n'est créé qu'après PlaybackInfo.

## Alternatives
- **Leanback** : obsolète, remplacé par Compose for TV. Rejeté.
- **Glide** : plus lourd, moins naturel avec Compose. Rejeté.
- **Jeton en `api_key` dans l'URL** : plus simple pour mpv et les images, mais il finit dans les journaux du serveur et de mpv. Rejeté (CLAUDE.md, ADR 0007).
- **`EncryptedSharedPreferences`** : la bibliothèque `security-crypto` est dépréciée. Rejeté au profit du Keystore direct.
- **Baisser le niveau de journal de mpv après l'init** : `libplayer.so` le fixe lui-même, et `msg-level` ne filtre pas ce qu'il recopie. Rejeté.

## Conséquences
- Un `MpvPlayerEngine` sert un seul jeu d'en-têtes : un nouveau serveur ou un nouveau jeton demande un nouveau moteur. C'est déjà le cas, puisqu'il y a un moteur par `PlayerActivity`.
- Les sous-titres externes (transcodage) passent par les mêmes en-têtes, posés une seule fois.
- Les séries n'ont pas encore d'écran : choisir une série affiche un avis.
