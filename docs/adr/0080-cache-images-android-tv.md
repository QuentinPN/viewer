# ADR 0080 — Cache des images dans les réglages (Android TV)

Statut : Accepté (2026-11-10). Ajoute une section « Stockage » aux réglages TV (ADR 0051), avant « À propos » (ADR 0076).

## Contexte
Les affiches, fonds et logos passent par le `ImageLoader` Coil construit dans `AppServices.images(...)`. Le Shield a peu de stockage, et rien ne montrait la place prise par ces images ni ne permettait de la rendre. CLAUDE.md : le cache est jetable, Emby reste la source de vérité.

Faits relevés dans Coil 3.6.3 (bytecode de `coil-core-android-3.6.3`, cache Gradle, sans jar de sources) avant cette décision :
- Le `ImageLoader` avait déjà un cache disque, celui de Coil par défaut : `ImageLoader.Builder.build()` prend `coil3.disk.UtilsKt.singletonDiskCache()` quand aucun n'est donné, un `DiskCache` unique au processus.
- Son dossier : `FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "coil3_disk_cache"`, soit `java.io.tmpdir`, qu'Android fait pointer vers `cacheDir` de l'application.
- Sa taille : défauts de `DiskCache.Builder`, `maxSizePercent = 0.02` de l'espace libre au moment de la création, borné entre `minimumMaxSizeBytes = 10485760` (10 Mo) et `maximumMaxSizeBytes = 262144000` (250 Mo). La limite variait donc avec l'espace libre et n'était écrite nulle part dans le dépôt.
- Le cache mémoire est celui par défaut de chaque `ImageLoader` (un par session, `remember(client)` dans `MainActivity`).

## Décision
- **Cache disque explicite et borné** : `DiskCache.Builder().directory(cacheDir / "coil3_disk_cache").maxSizeBytes(256 Mo)`, passé par `ImageLoader.Builder.diskCache { … }`. Une seule instance par processus dans `AppServices`, partagée par tous les `ImageLoader` : deux caches sur le même dossier abîmeraient son journal (c'est aussi pourquoi Coil en fait un singleton).
  - Même dossier que le cache par défaut : les images déjà enregistrées restent utilisables, et aucun ancien dossier n'est laissé orphelin.
  - **256 Mo fixes** (268 435 456 octets) : une affiche en `maxWidth` 320 à 560 pèse quelques dizaines de ko, un fond en 1920 quelques centaines ; 256 Mo en gardent des milliers. C'est à peu près la limite que Coil prenait déjà dès 12,5 Go libres, mais elle ne dépend plus de l'espace libre au lancement et peut s'afficher telle quelle. Au-delà, `DiskLruCache` retire les entrées les plus anciennes ; sous `cacheDir`, Android peut aussi le vider quand le stockage manque.
- **Section « Stockage »** des réglages, entre « Sous-titres » et « À propos », dans le style des lignes existantes :
  - une note : les images déjà affichées restent sur l'appareil ; les vider ne change rien sur le serveur Emby, elles seront de nouveau téléchargées ;
  - « Cache des images » (ligne en lecture seule) : « 84 Mo sur 256 Mo », d'après `DiskCache.size` et `DiskCache.maxSize` ; « Calcul en cours… » le temps de la lecture, « Taille inconnue » si elle échoue ;
  - « Vider le cache » : OK appelle `MemoryCache.clear()` puis `DiskCache.clear()` du `ImageLoader` de la session, puis relit la taille. La valeur affiche « En cours… » (un second OK est ignoré), puis « Vidé » ou « Échec ».
- **Hors du fil de l'interface** : la mesure (le journal du cache est lu à la première utilisation) et le vidage tournent sur `Dispatchers.IO`.
- **Pas de confirmation** : vider ne perd rien, les images reviennent du serveur au prochain affichage. Les réglages n'ont aucune boîte de dialogue ; chaque ligne agit sur OK.
- **Logique pure testée** : `settings/ImageCache.kt` (dossier, borne, tailles en français « 84 Mo », « 1,2 Go », « moins de 1 Mo », texte de la ligne, états du vidage). Unités binaires (1 Mo = 1 048 576 octets), pour que la borne s'affiche « 256 Mo ».

## Sécurité
- **Aucun jeton dans les URL d'images.** Toutes viennent de `EmbyClient.imageUrl` : `/Items/{id}/Images/{type}?tag=…&maxWidth=…`. Le client d'images ajoute l'en-tête `X-Emby-Authorization` (jeton compris) par un intercepteur OkHttp, et seulement pour l'origine du serveur (schéma, hôte, port).
- **Ce qui est écrit sur le disque** : Coil utilise l'URL comme clé et enregistre le corps de la réponse avec le code, les heures et les en-têtes **de la réponse** (`CacheNetworkResponse.writeTo`). Les en-têtes de la requête, dont l'autorisation, n'en font pas partie.
- **Portée** : le cache n'est séparé par serveur et par utilisateur que par l'URL (l'hôte du serveur y figure). Deux comptes du même serveur partagent les images d'un même élément ; il faut connaître l'identifiant et le `tag` de l'élément, que seul le serveur donne à un compte qui y a accès. Le cache reste dans le stockage privé de l'application.
- Rien n'est journalisé.

## Alternatives
- **Garder le cache par défaut** : il marche, mais sa limite dépend de l'espace libre au lancement et n'est écrite nulle part. Écarté.
- **Un pourcentage de l'espace** (`maxSizePercent`) : la limite affichée changerait d'un lancement à l'autre. Écarté au profit d'une valeur fixe.
- **Un dossier séparé par serveur ou par compte** : complique le partage du singleton pour des images qu'Emby redonne de toute façon. Écarté.
- **Vider aussi à la déconnexion** : non demandé ; possible plus tard avec le même appel.

## Conséquences
- `SettingsScreen` reçoit le `ImageLoader` de la session ; `MainActivity` le lui passe (une ligne).
- Vider le cache fait recharger les images des écrans encore ouverts sous les réglages quand ils se recomposent.
- Réversible : retirer `diskCache { … }` rend le cache par défaut de Coil, dans le même dossier.

## Vérification
- Tests unitaires `ImageCacheTest` (7) : 0 et quelques octets, arrondi des Mo, passage aux Go juste sous 1 024 Mo, virgule décimale, texte « … sur 256 Mo », états de la ligne, valeur de la borne. `testDebugUnitTest` (360 tests), `assembleDebug` et `lintDebug` passent, sans avertissement lint dans les fichiers modifiés.
- Non vérifié sur le Shield : l'affichage et le focus des deux lignes, la taille réelle mesurée, le vidage puis le rechargement des images, la reprise des images déjà enregistrées par l'ancien cache par défaut.
