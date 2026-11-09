# ADR 0045 — Tri et filtres de la grille (Android TV)

Statut : Accepté (2026-11-09). Complète l'ADR 0044 ; reprend l'ADR 0013 (Windows).

## Contexte
La grille de bibliothèque de l'ADR 0044 ne triait que par titre. Sur 606 films, il faut pouvoir retrouver les derniers ajouts, un genre ou ce qui n'a pas encore été vu.

## Décision
- **Mêmes choix que sous Windows** (ADR 0013, `models.rs`), dans `emby/ItemsOptions.kt` :
  - tri par titre (`SortName`), date d'ajout (`DateCreated,SortName`), année (`ProductionYear,SortName`) ou note (`CommunityRating,SortName`). A → Z par défaut pour le titre, décroissant pour les autres ;
  - visionnage : tous, non vus (`IsUnplayed`), vus (`IsPlayed`), en cours (`IsResumable`, films seulement) ;
  - favoris (`IsFavorite`) ;
  - un seul genre (`Genres`). Un nom vide, de plus de 100 caractères, avec `|` ou un caractère de contrôle n'est jamais envoyé.
- **Requête** : `Filters` et `Genres` ne sont ajoutés que s'ils filtrent quelque chose. La liste des genres vient de `GET /Genres` pour la bibliothèque, lue une fois quand la liste s'ouvre.
- **Télécommande** : une rangée de boutons au-dessus de la grille. Tri, Visionnage et le sens changent à chaque appui ; Favoris s'active ou non ; Genre ouvre une liste latérale ; Réinitialiser revient aux choix par défaut. Un bouton qui filtre est plein, les autres en contour. Le focus reste sur la barre pendant le rechargement.
- **Rechargement** : chaque changement repart de la page 0, avec un nouvel état de grille. La collecte des pages redémarre, ce qui annule une page en route ; une page de l'ancienne requête est ignorée.
- **Mémoire** : choix gardés par utilisateur et par bibliothèque dans des préférences simples, comme les pistes (ADR 0042) : rien de secret.
- **État vide** : « Aucun titre ne correspond à ces filtres. » quand un filtre est actif, sinon « Cette bibliothèque est vide. ».

## Alternatives
- **Un menu latéral unique pour tous les choix** : un appui de plus pour chaque changement. Les boutons qui défilent leurs valeurs suffisent pour 2 à 4 valeurs ; seule la liste des genres en a besoin.
- **Plusieurs genres à la fois** : comme sous Windows, un seul pour garder une liste simple.
- **Composant `FilterChip`** : encore expérimental dans Compose for TV ; boutons pleins et en contour, comme les saisons.

## Conséquences
- Le bouton pressé est recréé quand il passe de contour à plein : il reprend le focus lui-même.
- La liste des genres n'empêche pas de sortir sur le côté avec la croix directionnelle.
- Les genres mêlent les langues (« Science-Fiction », « Sci-Fi »), tels que le serveur les renvoie.
