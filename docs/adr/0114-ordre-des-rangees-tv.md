# ADR 0114 — Ordre des rangées de l'accueil (Android TV)

Statut : Accepté (2026-11-13). Complète l'ADR 0094. Pendant bureau : ADR 0113.

## Contexte
Depuis l'ADR 0094, chaque rangée de l'accueil peut être masquée depuis Réglages › Accueil, mais l'ordre reste fixe : « Reprendre », « À suivre », « Favoris », un « Derniers ajouts » par bibliothèque, puis « Listes de lecture ». Le propriétaire veut aussi choisir cet ordre, à la télécommande. L'ADR 0094 l'avait laissé pour plus tard. La version bureau fait de même (ADR 0113).

## Décision
### Stockage
- L'ordre reste sur l'appareil, par utilisateur Emby, dans les mêmes préférences locales `home` : clé `<userId>.order`, les clés des rangées (`resume`, `nextUp`, `favorites`, `latest.<libraryId>`, `playlists`) séparées par un saut de ligne. Un `StringSet` n'a pas d'ordre, d'où une chaîne. Rien n'est stocké tant que l'utilisateur ne déplace rien : l'ordre par défaut s'applique.
- Fusion avec les rangées du moment (`arrangedKeys`) :
  - une clé stockée inconnue (bibliothèque supprimée) ou en double est ignorée ; elle reste stockée, en fin de liste, pour une bibliothèque qui reviendrait ;
  - une rangée absente de l'ordre stocké (nouvelle bibliothèque) se place juste après la rangée qui la précède dans l'ordre par défaut ; sans prédécesseur placé, juste avant celle qui la suit ; sinon à la fin. Une nouvelle bibliothèque arrive donc à côté des autres « Derniers ajouts », là où l'utilisateur les a mis.
- Masquer et ordonner sont indépendants : une rangée masquée garde sa place et la retrouve quand elle est réaffichée.

### Réglages › Accueil (télécommande)
- La liste suit l'ordre choisi. OK bascule toujours « Affichée » / « Masquée ».
- Un **appui long sur OK** passe la rangée en déplacement : elle est mise en évidence (`selected`), sa valeur devient « Déplacement » et une ligne rappelle « ▲ ▼ pour déplacer, OK ou Retour pour poser ». ▲ ▼ la montent ou la descendent d'un cran (bornée aux extrémités), chaque cran est enregistré aussitôt ; ◀ ▶ sont ignorés pour que le focus ne quitte pas la ligne ; OK ou Retour la posent. Les répétitions et le relâchement de l'appui long lui-même ne la posent pas.
- C'est le geste déjà utilisé pour le menu d'une carte (ADR 0097/0098) : appui long = action secondaire. Un mode « Réorganiser » séparé ou un menu « Monter / Descendre » demandaient plus d'appuis pour le même résultat.
- La ligne garde sa clé dans la `LazyColumn` : le focus la suit quand elle change de place. Le glissement (`animateItem`) est coupé quand l'animation est réduite (`reducedMotion()`).

### Accueil
- Les rangées s'affichent dans l'ordre choisi (`arrangedShelves`, tri stable), au chargement (`homeShelves`) et à l'affichage, donc dès le retour des Réglages sans nouvelle requête. Une rangée masquée n'est toujours pas demandée.

## Alternatives
- **Mode « Réorganiser » global** (un bouton, puis ▲ ▼ sur n'importe quelle ligne) : un état de plus à expliquer, et la ligne « Réorganiser » s'ajoute à la liste. Rejeté.
- **Menu par rangée « Monter / Descendre / Masquer »** : un dialogue à rouvrir à chaque cran. Rejeté.
- **Placer toute nouvelle rangée à la fin** : plus simple, mais une nouvelle bibliothèque atterrirait après « Listes de lecture ». Rejeté.
- **Ordre sur le serveur** : même raison que l'ADR 0094. Rejeté.

## Conséquences
- `StringSetPrefs` gagne `getString` / `putString`.
- Un ordre changé ne recharge pas l'accueil : seules les rangées déjà chargées sont triées.

## Vérification
- `HomeRowOrderTest` (9 tests) : ordre par défaut, ordre stocké, clés inconnues et doublons ignorés, nouvelle bibliothèque placée après sa voisine, rangée manquante en tête placée avant sa suivante, déplacement borné, aller-retour du format stocké, ordre par utilisateur avec bibliothèque disparue conservée, rangées de l'accueil dans l'ordre stocké.
- `testDebugUnitTest` (587 tests, 0 échec), `assembleDebug` et `lintDebug` passent.
- Non vérifié sur la Shield : appui long, suivi du focus pendant le déplacement, Retour qui pose la rangée sans quitter les Réglages.
