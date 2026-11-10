# ADR 0068 — « Distribution » et page personne (Android TV)

Statut : Accepté (2026-11-10). Complète les ADR 0050, 0060, 0063 et 0067.

## Contexte
Les pages film, série et épisode ne montraient la distribution qu'en texte : une ligne « Avec … » (six acteurs) ou « Invités … » sur un épisode (`Credits.kt`, ADR 0016). Impossible d'y voir un visage ni de savoir dans quels autres titres de la bibliothèque joue un acteur. Emby fournit pourtant, pour chaque personne d'un élément, son identifiant, son rôle et l'étiquette de sa photo, et sait filtrer la bibliothèque par personne.

## Contrat Emby vérifié
Documentation officielle, consultée le 2026-11-10 :
- `ItemsService` → [`GET /Users/{UserId}/Items`](https://dev.emby.media/reference/RestAPI/ItemsService/getUsersByUseridItems.html) (même liste de paramètres que [`GET /Items`](https://dev.emby.media/reference/RestAPI/ItemsService/getItems.html)) : `PersonIds` (« results will be filtered to include only those containing the specified person »), `Recursive`, `IncludeItemTypes` (liste séparée par des virgules), `SortBy` (dont `ProductionYear` et `SortName`, plusieurs clés séparées par des virgules), `SortOrder` (`Ascending`, `Descending`), `Limit`, `Fields`, `EnableUserData`, `ImageTypeLimit`, `EnableImageTypes`. Réponse 200 : `QueryResult_BaseItemDto` (`Items`, `TotalRecordCount`), le `QueryResult<BaseItemDto>` déjà décodé.
- `UserLibraryService` → [`GET /Users/{UserId}/Items/{Id}`](https://dev.emby.media/reference/RestAPI/UserLibraryService/getUsersByUseridItemsById.html) : renvoie un `BaseItemDto` dont `People` est un tableau de [`BaseItemPerson`](https://dev.emby.media/reference/pluginapi/MediaBrowser.Model.Dto.BaseItemPerson.html) : `Name`, `Id`, `Role`, `Type` (`PersonType`), `PrimaryImageTag` (et `HasPrimaryImage`, non lu).
- La photo est l'image `Primary` de l'élément personne : `GET /Items/{Id}/Images/Primary` avec `tag` et `maxWidth`, le chemin déjà employé par `EmbyClient.imageUrl`, chargé par Coil avec l'en-tête `X-Emby-Authorization` (jamais de jeton dans l'URL).

`Id` d'une personne est documenté comme chaîne, et les fixtures Windows vérifiées sur Emby 4.10 l'envoient ainsi ; mais `Studios[].Id` y est un nombre (ADR 0063). `PersonDto` lit donc `Id` comme n'importe quelle valeur primitive : un nombre ne rendrait pas toute la page illisible. Aucun appel au serveur réel n'a été fait pour ce lot.

## Décision
- **Rangée « Distribution »** (`ui/CastRow.kt`) sur les pages film, série et épisode, sous les détails et avant « Titres similaires ». Jusqu'à 12 personnes dans l'ordre du serveur (ordre d'affiche) : les acteurs (`Actor`) d'un film ou d'une série, les invités (`GuestStar`) d'un épisode, comme l'ancienne ligne « Invités ». Une personne sans identifiant utilisable (vide, ou refusé par `segment`) ou sans nom est omise : elle n'aurait ni page ni photo ; les doublons aussi (`castMembers`).
- **Carte** : photo ronde de 112 dp recadrée par le haut (les photos Emby sont en portrait), initiales en dessous qui restent visibles pendant le chargement ou sans photo (`initials` : première lettre du premier et du dernier mot, « ? » sans lettre), nom puis rôle sur deux lignes au plus. Focus des contrôles en verre (ADR 0067) : anneau d'accent décollé de `RING_GAP`, agrandissement de 6 % supprimé quand l'animation système est coupée (`reducedMotion`).
- **Faits** : la ligne « Avec » / « Invités » disparaît, la rangée la remplace ; « Réalisation », « Scénario » et « Studio » / « Diffusion » restent.
- **Page personne** (`ui/PersonScreen.kt`) : photo de 160 dp, nom, résumé court (4 lignes) s'il existe, puis « Dans la bibliothèque » avec le nombre de titres et la grille d'affiches de la bibliothèque (`ItemCard`). Requête `EmbyClient.personItems` : `/Users/{UserId}/Items` avec `PersonIds`, `Recursive=true`, `IncludeItemTypes=Movie,Series`, `SortBy=ProductionYear,SortName`, `SortOrder=Descending`, `Limit=100`, `Fields=PrimaryImageAspectRatio,ProductionYear`, `EnableUserData=true`, `ImageTypeLimit=1`, `EnableImageTypes=Primary`. Un seul `SortOrder` s'applique à toutes les clés : le client retrie donc par année décroissante puis titre A → Z, sans année en dernier, films et séries seulement, sans doublon (`personTitles`).
- **Résumé** : `GET /Users/{UserId}/Items/{PersonId}` (`EmbyClient.item`), lu à part ; un échec ne montre que le nom et la photo.
- **États** : « Chargement… », erreur avec « Réessayer », vide : « Aucun titre de cette personne dans la bibliothèque. » ; l'en-tête reste affiché dans les trois cas. Une session expirée renvoie à la connexion, comme partout.
- **Navigation** : la pile de pages de `MainActivity` (ADR 0063) reçoit `DetailPage.Person` et `DetailPage.Episode`. La fiche épisode, jusqu'ici une variable au-dessus de la pile, y entre pour que Retour depuis une personne ouverte d'un épisode ramène à l'épisode. Son comportement est inchangé : précédent / suivant remplacent la page du dessus dans la même composition (`pageKey`), Retour remplace la série dessous par la bonne saison et la bonne carte (`backFromEpisode`).
- **Retour et focus** : ouvrir une personne mémorise la carte dans la page dessous (`person` de `DetailPage.Movie` / `Series` / `Episode`) ; la page, recomposée à neuf, place la rangée sur cette carte et lui rend le focus au lieu du bouton « Lire » ou de la carte d'épisode. Ouvrir un titre depuis une page personne mémorise ce titre (`opened`) : Retour revient sur son affiche. Un titre similaire ou un épisode efface la mémoire (`openOver`), pour qu'une carte choisie plus tôt ne reprenne pas le focus.

## Alternatives
- **Garder la ligne « Avec » en plus de la rangée** : la même information deux fois, et la place manque sur la fiche épisode. Écarté.
- **`GET /Persons/{Name}`** (`PersonsService`) : adressé par le nom, ambigu entre homonymes et à encoder dans le chemin ; l'identifiant de `People` suffit à tout. Écarté.
- **Inclure les épisodes (`IncludeItemTypes=Episode`)** sur la page personne : les invités d'une série rempliraient la grille d'épisodes isolés ; la série ouvre déjà ses épisodes. Écarté pour ce lot.
- **Rangées par type (Films, Séries)** au lieu d'une grille : deux rangées à parcourir pour une filmographie souvent courte. Écarté ; une grille triée par année suffit.
- **Garder les pages montées sous la page personne** pour retrouver le focus exact : même raison que les ADR 0060 et 0063. Écarté ; la page mémorise la carte.

## Conséquences
- Une page personne coûte deux requêtes (titres, résumé), plus une photo par affiche.
- Retour vers une page de la pile la relit (bref « Chargement… »), comme depuis un titre similaire.
- La fiche épisode défile désormais verticalement, comme la page film : les détails remplissent l'écran, Bas depuis les boutons atteint « Distribution ».
- Les invités d'un épisode sans identifiant n'apparaissent plus du tout (ils figuraient dans la ligne « Invités »).

## Vérification
- Tests unitaires : `EmbyClientTest.personItemsAsksForTheFilmsAndSeriesOfOnePerson` (chemin, paramètres, jeton absent de l'URL, ni `api_key` ni `X-Emby-Token`, présent dans `X-Emby-Authorization`), `personItemsRejectsAnIdThatWouldChangeThePath`, `peopleOfAnItemCarryTheirIdRoleAndPhoto` (identifiant chaîne ou nombre, rôle, photo) ; `CastRowTest` (acteurs et invités, ordre, limite, identifiants refusés, initiales, pile : personne, titre d'une personne, titre similaire, épisode et Retour) ; `PeopleTest` (requête, tri) ; `FactsTest` et `EpisodePageTest` mis à jour. `testDebugUnitTest` (262 tests, 0 échec), `assembleDebug` et `lintDebug` passent, sans avertissement de lint dans les fichiers changés.
- Non vérifié sur le Shield ni sur le serveur : la réponse réelle de `PersonIds` (titres, tri, `ProductionYear`), le résumé d'une personne, les photos et leur recadrage, la place de la rangée à 1080p, le défilement de la fiche épisode, Retour avec le focus sur la carte de la personne et sur l'affiche ouverte.
