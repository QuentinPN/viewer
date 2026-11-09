# ADR 0047 — Recherche (Android TV)

Statut : Accepté (2026-11-09). Reprend les ADR 0011 et 0020 (Windows).

## Contexte
Sur Android TV, on ne trouvait un titre qu'en parcourant les rangées de l'accueil ou une grille de bibliothèque. Le client Windows cherche les films, les séries et les épisodes avec le paramètre `SearchTerm` de `GET /Users/{UserId}/Items`, vérifié sur le serveur (ADR 0011, 0020). La saisie se fait ici à la télécommande : clavier à l'écran ou voix, fournis par le système.

## Décision
- **Même requête que sous Windows** (`EmbyClient::search`, `src-tauri/src/emby/client.rs`), dans `EmbyClient.search` : `SearchTerm`, `Recursive=true`, `IncludeItemTypes` (`Movie`, `Series` ou `Episode`), `Limit`, `Fields=PrimaryImageAspectRatio,ProductionYear` (`PrimaryImageAspectRatio` pour les épisodes), `EnableImageTypes=Primary` (`Primary,Backdrop,Thumb` pour les épisodes), `ImageTypeLimit=1`, `EnableUserData=true`. Le jeton reste dans l'en-tête `X-Emby-Authorization`.
- **Même logique que `emby_search`** (`session.rs`), dans `emby/Search.kt` :
  - trois requêtes en parallèle : 25 films, 25 séries, 100 épisodes ; 24 affichés par type, un 25ᵉ signale qu'il y en a d'autres ;
  - texte nettoyé de la même façon (caractères de contrôle retirés, espaces réduits, 100 caractères au plus) ; en dessous de 2 caractères, aucun appel ;
  - seuls les épisodes dont **le titre lui-même** commence un mot par la recherche sont gardés (`titleMatches`, même tableau de 19 lettres accentuées, « œ » non replié) ; une page de 100 épisodes pleine signale que d'autres peuvent correspondre.
- **Écran** : un champ « Recherche » (clavier du système, touche « Rechercher » pour lancer tout de suite), puis une rangée par type, « Films », « Séries », « Épisodes », de cartes de l'accueil (`ItemCard`) : affiches pour les films et séries, 16:9 pour les épisodes. Les messages « Seuls les 24 premiers films… » et « D'autres épisodes peuvent correspondre… » reprennent ceux de Windows.
- **Saisie** : la recherche part 300 ms après la dernière lettre, un peu plus que sous Windows (250 ms), car le clavier à l'écran est plus lent. Une nouvelle saisie annule la requête en cours, et une réponse qui ne correspond plus au champ est ignorée.
- **États** : invitation à saisir au moins deux lettres, « Recherche… », « Aucun film, série ou épisode pour « … » » avec le rappel que la recherche porte sur le début des mots, erreur avec « Réessayer ».
- **Ouvrir** : un film ou une série ouvre sa fiche. Sous Windows, un épisode ouvre sa propre fiche, sans lancer la lecture ; Android TV n'a pas encore de fiche d'épisode, donc la carte **ouvre la fiche de sa série**, où il peut être lu.
- **Navigation** : un bouton « Rechercher » à côté de « Déconnecter » dans l'en-tête de l'accueil. Retour revient à l'accueil, le focus sur ce bouton. Retour depuis une fiche ouverte par la recherche revient aux résultats, avec le texte, le défilement et le focus sur la carte (`SearchMemory`, comme `LibraryMemory`). Une nouvelle recherche repart d'un champ vide.

## Alternatives
- **Lancer la lecture d'un épisode** : c'est ce que fait la rangée « Reprendre », mais on cherche un titre, souvent pour vérifier lequel c'est ; Windows a écarté ce choix (ADR 0020). Écarté.
- **Une seule requête `Movie,Series,Episode`** : les épisodes d'une série dont le nom correspond rempliraient la page. Écarté, comme sous Windows.
- **`SearchBar` ou `TextField` de Material 3** : une dépendance de plus ; le champ `BasicTextField` de la connexion suffit.
- **Garder la recherche en revenant à l'accueil** : un bouton « Rechercher » qui rouvre l'ancienne saisie surprend ; on repart d'un champ vide.

## Conséquences
- La fiche de série s'ouvre sur sa première saison, pas sur celle de l'épisode trouvé.
- Pas d'historique de recherche : `RecentlySearched` serait une écriture sur le serveur.
- Les personnes et les genres ne sont pas cherchés, comme sous Windows.
- Le clavier à l'écran et la saisie vocale dépendent du clavier installé sur l'appareil.
