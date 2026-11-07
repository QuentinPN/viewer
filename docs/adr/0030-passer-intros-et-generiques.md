# ADR 0030 — Passer les intros et les génériques

Statut : Accepté (2026-11-07).

## Contexte
Le propriétaire a demandé le 2026-11-07 : « ajoute le support "skip intros & credits" comme sur Infuse en utilisant IntroDB.app et TheIntroDB.org ».

Les sources vérifiées le 2026-11-07 (`docs/evidence/intros-generiques-2026-11-07.md`) :
- **Emby :** `ChapterInfo.MarkerType` vaut `Chapter`, `IntroStart`, `IntroEnd` ou `CreditsStart` (référence REST `getItems`). `/Users/{Id}/Items/{Id}` renvoie `Chapters` et `ProviderIds` sans `Fields`. Mais sur le serveur de test, 300 épisodes et 200 films tirés au hasard n'ont **aucun** marqueur autre que `Chapter`. La documentation ne dit pas ce qui remplit ces marqueurs.
- **IntroDB** (`https://api.introdb.app`) : `GET /segments?imdb_id=…&season=…&episode=…`, ou `&is_movie=true` pour un film. Lecture anonyme, limitée en débit (429), sans chiffre publié. La réponse donne `intro`, `recap`, `outro` et `post_credits`, chacun `null` ou `{start_sec, end_sec, …}`, plus `no_intro` et `no_outro` quand la communauté a confirmé qu'il n'y en a pas. Les conditions d'utilisation permettent l'intégration dans un lecteur et l'usage personnel ; l'attribution est appréciée sans être exigée. Un identifiant inconnu donne 200 avec tout à `null`.
- **TheIntroDB** (`https://api.theintrodb.org/v4`, spécification OpenAPI 4.0.0) : `GET /media?tvdb_id|tmdb_id|imdb_id=…&season=…&episode=…`. Pour un film, l'identifiant seul ; `duration_ms` choisit la version de la sortie. Lecture anonyme : 30 requêtes par 10 s et 5000 par jour et par IP. La réponse donne les listes `intro`, `recap`, `credits` et `preview` de `{start_ms, end_ms}`, avec des bornes nulles : un début nul vaut le début du fichier, une fin nulle la fin du fichier. Sans données, la réponse est 404 avec `code` `no_submissions`, `media_not_found` ou `episode_not_found`. Aucune condition d'utilisation n'a été trouvée.
- Pour un épisode, les deux services attendent les identifiants de **la série**, avec la saison et l'épisode. L'épisode Emby ne porte que les siens : il faut lire la série. La casse des clés `ProviderIds` varie sur le serveur réel (`Imdb` sur un épisode, `IMDB` sur une série).

## Décision
- **Ordre des sources, par type de segment :** les marqueurs Emby, puis IntroDB, puis TheIntroDB.
  - Chaque type (intro, générique) vient de la première source qui en donne. TheIntroDB n'est interrogé que pour un type que ni Emby ni IntroDB n'ont donné, ou dont IntroDB n'a pas confirmé l'absence (`no_intro`, `no_outro`).
  - Le résumé (`recap`), les scènes post-générique et la bande-annonce de fin (`preview`) ne sont pas proposés dans cette tranche.
- **Identifiants envoyés :** uniquement ceux du film, ou de la série avec la saison et l'épisode. Rien d'autre : ni jeton, ni adresse du serveur, ni nom d'utilisateur, ni titre.
  - IntroDB reçoit l'identifiant IMDb. TheIntroDB reçoit un seul identifiant, le premier dans l'ordre TVDB, TMDB, IMDb, plus `duration_ms` (la durée du fichier Emby).
  - Les identifiants sont validés en Rust avant de construire l'URL (`tt` suivi de 7 ou 8 chiffres, entiers positifs). Sinon, la source n'est pas interrogée.
- **Vie privée :** le réglage « Chercher les intros et génériques sur IntroDB et TheIntroDB » est **activé par défaut**, comme l'a choisi le propriétaire le 2026-11-07. Désactivé, seuls les marqueurs Emby servent.
- **Passage automatique :** le réglage « Passer automatiquement les intros et génériques » est désactivé par défaut. Ces deux réglages sont des préférences de ce poste, dans le `localStorage` de la WebView (`viewer.player.skip`, sans secret), comme le volume (ADR 0017). L'interface les passe à la commande.
- **Commande :** `emby_segments(item_id, external)` renvoie des `Segment` `{kind: "intro" | "credits", start, end | null, source}` en secondes. Rust lit l'élément, et la série pour un épisode, puis construit et envoie les requêtes. L'interface ne construit aucune URL. Aucune nouvelle permission Tauri n'est nécessaire, car les commandes de l'application ne passent pas par les capacités.
- **Client HTTP des services externes :** un client `reqwest` séparé de celui d'Emby, sans en-tête Emby et sans redirection. Il valide le TLS. Délais : 3 s pour se connecter, 4 s par requête. La réponse lue est bornée à 64 Kio.
- **Cache :** en mémoire, par serveur, élément et réglage `external`, pour la durée du lancement de l'application, au plus 256 entrées (vidé quand il est plein).
  - Seules les réponses définitives sont gardées : des données, l'absence de données, ou un 404. Une coupure, un délai dépassé, un 429 ou une erreur 5xx ne sont pas gardés, et la lecture suivante réessaie.
  - Pas de cache sur disque : ces données sont légères et changent avec les contributions.
- **Hors ligne ou en échec :** aucun bouton, pas de message d'erreur, et la lecture n'est jamais retardée. Les segments arrivent en arrière-plan, comme l'épisode suivant (ADR 0018). Une réponse arrivée après un changement d'épisode est ignorée.
- **Lecteur :**
  - Pendant un segment, un bouton blanc en bas à droite, au-dessus de la barre : « Passer l'intro » ou « Passer le générique ». Il reste visible même quand les commandes s'effacent. Entrée l'active tant qu'il est affiché, sauf dans un panneau, un champ de texte ou sur la carte « Épisode suivant ». Il ne prend pas le focus de lui-même.
  - « Passer l'intro » va à la fin du segment.
  - Pour un générique qui finit avant la fin du fichier : « Passer le générique » va à sa fin, et « Épisode suivant » s'ajoute s'il y en a un.
  - Pour un générique qui court jusqu'à la fin du fichier (fin nulle, ou à moins de 2 s de la fin) : s'il y a un épisode suivant, le bouton devient « Épisode suivant » et le lance ; sinon, « Passer le générique » va à la fin, ce qui ferme le lecteur comme une fin naturelle.
  - Passage automatique : chaque segment n'est passé qu'une fois par lecture, à l'entrée. Revenir en arrière dedans ne le repasse pas. Un générique jusqu'à la fin avec un suivant ouvre la carte « Épisode suivant » et son compte à rebours (ADR 0018), qu'on peut annuler. Sans suivant, il laisse le générique se dérouler.
  - Le bouton n'apparaît pas dans les 0,5 dernière seconde du segment.

## Alternatives
- **Activer les services externes seulement sur demande :** c'est plus discret, mais le propriétaire a choisi l'activation par défaut. Le réglage reste disponible.
- **Interroger les deux services en parallèle :** c'est plus rapide, mais cela envoie les identifiants à TheIntroDB même quand IntroDB a déjà répondu. L'ordre séquentiel limite l'envoi au nécessaire.
- **Lancer la détection d'intro d'Emby :** cela change la configuration du serveur, ce qui demande l'accord du propriétaire. La lecture des marqueurs est prête si la détection est activée un jour.
- **Cache SQLite :** il est reporté avec le cache des métadonnées (ADR 0019). Un cache en mémoire suffit pour ne pas redemander pendant un lancement.

## Conséquences
- Une lecture d'épisode coûte une requête Emby de plus (la série) et au plus deux requêtes externes par lancement de l'application.
- Les identifiants IMDb, TMDB ou TVDB des titres regardés partent vers deux services tiers, qui voient l'IP du poste. C'est indiqué dans Réglages.
- Les données sont communautaires : un segment peut être faux ou viser une autre version du fichier. TheIntroDB reçoit la durée pour choisir la bonne version ; IntroDB ne la prend pas.
- Réversible : couper le réglage suffit, et retirer la commande ne touche à rien d'autre.

## Validation
- Tests Rust sur les réponses réelles enregistrées le 2026-11-07 : analyse des trois sources, bornes nulles, `no_*`, 404, validation des identifiants, ordre et fusion, cache, et un faux serveur local pour les statuts HTTP.
- Tests Vitest : segment actif, cible du saut, action du bouton, réglages lus et enregistrés.
- Non vérifié : le bouton dans le vrai lecteur sur un épisode avec des données. Le serveur de test n'a aucun marqueur, et aucun épisode de sa bibliothèque n'a été vérifié chez IntroDB ou TheIntroDB.
