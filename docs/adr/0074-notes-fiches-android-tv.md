# ADR 0074 — Notes sur les fiches film, série et épisode (Android TV)

Statut : Accepté (2026-11-10). Complète la ligne de l'année de l'ADR 0050 ; la fiche épisode (ADR 0060) gagne la même ligne de notes.

## Contexte
Depuis l'ADR 0050, les pages film et série montrent la classification en texte simple et la note du public (« ★ 7,4 »), noyées parmi les genres. La note des critiques, que Windows affiche (« Critiques : 17 % » dans `ItemDetail.svelte`), manquait, et la page épisode n'avait aucune note.

Ce que renvoie le serveur, vérifié sur le code et non sur un nouvel appel :
- `CommunityRating` (sur 10), `CriticRating` (sur 100) et `OfficialRating` sont des champs de base de `BaseItemDto`, pas des options de `Fields`. La réponse réelle `/Users/{Id}/Items/30207` d'Emby 4.10, gardée en fixture par Windows (`src-tauri/src/emby/tests.rs`), les contient tous les trois ; `CriticRating` y est un entier (`17`).
- Film et épisode sont lus par `fullItem` (sans `Fields`), la série par `item` (`Fields=Overview`) : l'appel d'un seul élément renvoie le détail complet dans les deux cas (genres et studios s'affichent déjà sur la série). Aucune requête ni aucun `Fields` de plus.
- `BaseItemDto` Android ne lisait pas `CriticRating` : ajouté en `Double?`, qui accepte l'entier comme un décimal.

## Décision
- **Ligne de l'année** des trois pages, dans cet ordre : année (date de diffusion pour un épisode), durée, badge de classification, genres (film et série), « ★ 7,4 », « 87 % critiques », puis « Vu ».
- **Classification** : badge à contour fin (1 dp, `Palette.textSecondary`, coins de 6 dp), texte du serveur tel quel (« 12 », « TV-MA »). Plus discret que la pastille pleine « Vu ». Lu « Classification 12 » par l'accessibilité.
- **Note du public** : une décimale, virgule française ; cachée si absente ou à 0 (non notée), comme sur Windows.
- **Note des critiques** : arrondie à l'entier, « 87 % critiques » avec une espace insécable avant « % » ; cachée si absente ou hors de 0–100. 0 % reste affiché : c'est une vraie note.
- **Épisode** : pas de genres (un épisode en a rarement à lui) ; classification et notes seulement.
- **Logique pure** dans `emby/Ratings.kt` (`communityRatingLabel`, `criticRatingLabel`, `ageRatingLabel`), testée par `RatingsTest` ; l'assemblage de la ligne (`tags`, `scores`) reste dans `ui/Credits.kt`, testé par `FactsTest`.
- **Accueil** : le héros montre déjà la classification en texte dans sa ligne (`heroFacts`) ; laissé tel quel.

## Alternatives
- **Logos IMDb / Rotten Tomatoes** : marques déposées et images à embarquer ; un libellé court suffit.
- **Ligne « Critiques » dans la fiche, comme Windows** : la fiche de la série est limitée à deux lignes pour la rangée d'épisodes ; une note y prendrait la place de la réalisation. Rejeté au profit de la ligne de l'année.
- **Classification en texte simple** (état précédent) : se confond avec les genres ; le badge la distingue sans attirer l'œil.

## Conséquences
- La classification n'est plus dans `tags` ; `RatingBadge` la dessine sur les trois pages.
- Sur une série, l'année n'est plus jointe au reste par « · » mais espacée comme sur la page film : le badge ne peut pas vivre dans un texte unique.
