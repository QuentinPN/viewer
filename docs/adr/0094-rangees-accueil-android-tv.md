# ADR 0094 — Rangées de l'accueil (Android TV)

Statut : Accepté (2026-11-10). Complète les réglages de l'ADR 0051 et l'accueil des ADR 0056, 0088, 0091 et 0092.

## Contexte
L'accueil empile, sous le bandeau, « Reprendre », « À suivre », « Favoris », une rangée « Derniers ajouts · <bibliothèque> » par bibliothèque de films ou de séries, puis « Listes de lecture ». Sur le serveur du propriétaire, cela fait huit rangées (Anime, Films, Films Anime, Séries TV). Certaines servent peu, et il faut beaucoup descendre pour atteindre les autres. Aucune rangée ne pouvait être retirée.

## Décision
- Réglages gagne une section « Accueil » : une ligne par rangée, dans l'ordre de l'accueil. OK bascule entre « Affichée » et « Masquée ». Par défaut, tout est affiché.
- Le choix reste sur l'appareil, par utilisateur Emby, dans les préférences locales `home` (clé `<userId>.hidden`, l'ensemble des rangées masquées). Les rangées fixes ont une clé fixe (`resume`, `nextUp`, `favorites`, `playlists`). Une rangée « Derniers ajouts » est repérée par l'id de sa bibliothèque (`latest.<id>`), donc un renommage garde le choix. Une clé inconnue ne masque rien et reste stockée, pour une bibliothèque qui reviendrait. Une nouvelle bibliothèque est affichée.
- La liste des bibliothèques vient de la même requête des vues que l'accueil et sa barre (`homeLibraries`, filtre `movies`/`tvshows`). Aucun nouvel appel n'est ajouté. Si cette requête échoue, les rangées fixes restent réglables et un message le signale.
- L'accueil ne demande pas les données d'une rangée masquée : ni « À suivre », ni les favoris, ni les listes de lecture, ni les derniers ajouts d'une bibliothèque masquée. « Reprendre » est toujours demandé, car le bandeau s'en sert. Quand « Reprendre » est masqué, ses épisodes ne sont plus retirés de « À suivre ».
- L'accueil sort de la composition pendant que les Réglages sont ouverts. Au retour, il relit les choix. Une rangée qui vient d'être masquée disparaît tout de suite (`visibleShelves`), avant le rechargement. Les choix utilisés au dernier chargement sont gardés dans `HomeMemory.loadedRows`, à côté de `loadedFor` : quand ils changent, l'accueil recharge ses rangées, et une rangée de nouveau affichée réapparaît.
- Le bandeau n'est pas concerné. Si tout est masqué et qu'il n'y a pas de bandeau, l'accueil indique que les rangées se rétablissent dans Réglages › Accueil.

## Alternatives
- **Garder le choix sur le serveur (DisplayPreferences)** : la version bureau ne le fait pas, et l'utilisateur ne s'attend pas à ce qu'un choix fait sur le téléviseur change ses autres appareils. Rejeté.
- **Changer l'ordre des rangées** : utile, mais plus lourd à manier à la télécommande. Laissé pour plus tard.
- **Masquer seulement à l'affichage** : simple, mais l'accueil continuerait à demander des données qui ne servent à rien. Rejeté.

## Conséquences
- Moins de rangées à l'accueil, donc moins de requêtes à chaque ouverture et après chaque lecture.
- Changer un réglage recharge l'accueil une fois au retour. Le focus revient sur la carte ouverte en dernier si elle est encore là, sinon sur le bandeau ou sur la première carte.

## Vérification
- `HomeRowsTest` (11 tests) : tout est affiché par défaut ; la liste suit l'ordre de l'accueil ; une bascule masque puis réaffiche et reste stockée ; chaque utilisateur garde ses choix ; une clé inconnue ne masque rien et reste stockée ; seules les bibliothèques affichées sont demandées ; les rangées masquées sont retirées, y compris celles déjà à l'écran ; « À suivre » garde l'épisode de « Reprendre » quand « Reprendre » est masqué ; les bibliothèques sont celles de la barre.
- `testDebugUnitTest` (471 tests, 0 échec), `assembleDebug` et `lintDebug` passent.
