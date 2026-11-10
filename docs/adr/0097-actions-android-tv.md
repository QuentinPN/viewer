# ADR 0097 — Vu, favori et « Reprendre » (Android TV)

Statut : Accepté (2026-11-10). Première écriture de l'application TV sur les données de l'utilisateur. Complète l'accueil des ADR 0056, 0091 et 0094 et les fiches des ADR 0058, 0060 et 0083.

## Contexte
L'application TV ne faisait que lire. Pour marquer un titre comme vu, le retirer des favoris ou sortir un épisode abandonné de « Reprendre », il fallait passer par la version bureau ou par Emby Web. La version bureau envoie déjà `set_favorite` (ADR 0014) et `set_played` (`src-tauri/src/emby/client.rs`).

## Décision
- `EmbyClient` gagne trois appels, tous documentés sur dev.emby.media :
  - `setPlayed` : `POST` / `DELETE /Users/{UserId}/PlayedItems/{Id}`. `DatePlayed` est facultatif et n'est pas envoyé : le serveur date lui-même la lecture.
  - `setFavorite` : `POST` / `DELETE /Users/{UserId}/FavoriteItems/{Id}`.
  - `hideFromResume` : `POST /Users/{UserId}/Items/{Id}/HideFromResume?Hide=true|false`.
  Chacun renvoie le `UserItemDataDto` du titre, lu dans `UserItemData`, qui gagne `IsFavorite`. Le jeton reste dans l'en-tête `X-Emby-Authorization`, jamais dans l'URL. Les identifiants passent par `segment` avant d'entrer dans le chemin. Les erreurs suivent les catégories habituelles.
- Fiches film et épisode : une ligne sous les boutons de lecture, « Marquer comme vu » / « Marquer comme non vu » et « Ajouter aux favoris » / « Retirer des favoris ». La réponse du serveur remplace les données de l'utilisateur affichées (pastille « Vu », reprise).
- Fiche série : la ligne de « Bande-annonce » devient celle des actions, sur toute la largeur. On y trouve « Bande-annonce », « Marquer la série comme vue / non vue », le favori de la série, puis « Marquer la saison comme vue / non vue » pour la saison affichée. Une saison n'a pas de fiche à elle : son bouton est ici.
- Sur une série ou une saison, Emby Web se sert de `PlayedItems` pour marquer tous les épisodes, mais **cette cascade n'est pas documentée**. L'application ne la suppose donc pas. Après la réponse, elle relit la série, ses saisons et les épisodes de la saison affichée, sans quitter l'écran. Les marques affichées sont celles du serveur.
- Accueil : un appui long sur OK sur une carte ouvre un petit menu, centré sur la carte. Il propose « Retirer de Reprendre » (seulement dans « Reprendre »), « Marquer comme vu / non vu » et « Ajouter aux / Retirer des favoris ». Il n'y en a pas sur une liste de lecture. Le menu est une `Popup` focusable, comme le menu du compte : Retour le ferme et le focus revient sur la carte. Il reste ouvert pendant l'envoi et affiche le message en cas d'échec.
- Après un succès sur l'accueil, `shelvesAfter` met à jour les rangées tout de suite. La carte quitte « Reprendre » si elle en a été retirée ou si le titre est vu, puisque le serveur n'y liste que les titres en cours. Elle quitte « Favoris » si le titre n'est plus favori. Ailleurs, elle prend ses nouvelles données. `focusAfter` place le focus sur la carte, ou sur celle qui a pris sa place, ou sur la précédente. L'accueil relit ensuite ses rangées sur place, comme après une lecture (`HomeMemory.changes`). Une action faite sur une fiche incrémente aussi ce compteur, et l'accueil est relu au retour.
- Pendant l'envoi, un bouton est grisé et un nouvel appui ne fait rien. Il reste focusable, pour que le focus ne saute pas ailleurs. En cas d'échec, un message court s'affiche (« Action impossible. » suivi de la cause) et l'état précédent reste. Une session expirée renvoie à la connexion, comme ailleurs.

## Alternatives
- **Marquer chaque épisode d'une série un par un** : beaucoup de requêtes, pour un résultat qu'Emby Web obtient en une seule. Rejeté. La relecture suffit à montrer ce que le serveur a vraiment fait.
- **Mise à jour optimiste avant la réponse** : plus réactive, mais il faudrait revenir en arrière en cas d'échec. La réponse arrive vite sur le réseau local. Rejeté.
- **Un bouton « … » sur chaque carte** : il ajouterait un arrêt du focus par carte dans chaque rangée. L'appui long est déjà le geste « autre action » des cartes d'épisode (lecture directe). Rejeté.
- **`enabled = false` pendant l'envoi** : non retenu, car un bouton TV désactivé pourrait perdre le focus (non vérifié sur `tv-material` 1.1.0).

## Conséquences
- Première écriture de l'app TV sur le serveur : les marques et les favoris changent aussi sur les autres appareils du compte, comme sur la version bureau.
- La fiche série a toujours une ligne de boutons, même sans bande-annonce, et la rangée d'épisodes descend d'une ligne.
- « Retirer de Reprendre » n'a pas d'annulation dans l'application. `Hide=false` existe dans le client, mais aucun écran ne l'utilise encore.

## Vérification
- `EmbyClientTest` (7 nouveaux tests) : méthode, chemin et requête de chaque appel ; pas de corps ; jeton dans l'en-tête et jamais dans l'URL ; lecture de la réponse ; session requise ; identifiants refusés avant tout envoi ; erreurs 401, 404, 500 et JSON invalide.
- `ItemActionsTest` (11 tests) : menu par rangée et par type ; chaque action est l'inverse de l'état affiché ; une carte quitte « Reprendre » ou « Favoris » quand il le faut et garde ses nouvelles données ailleurs ; une rangée vidée disparaît ; focus sur la carte, la suivante ou la précédente ; message en français ; libellés série et saison.
- `testDebugUnitTest` (494 tests, 0 échec), `assembleDebug`, `lintDebug`, `assembleRelease` et `lintRelease` passent.
- **Non vérifié** : rien n'a été essayé sur la Shield ni contre le serveur réel. Restent à vérifier : l'appui long sur les cartes de l'accueil, le retour du focus après la fermeture du menu, la cascade de `PlayedItems` sur une série ou une saison, et la place des nouvelles lignes de boutons sur les fiches.
