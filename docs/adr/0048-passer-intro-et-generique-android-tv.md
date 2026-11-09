# ADR 0048 — Passer l'intro et le générique (Android TV)

Statut : Accepté (2026-11-09). Reprend l'ADR 0030 (Windows) ; complète l'ADR 0043.

## Contexte
Le lecteur Android TV ne connaissait ni les intros ni les génériques. La carte « Épisode suivant » de l'ADR 0043 n'apparaissait qu'à la fin du fichier, après tout le générique.

Sous Windows, l'ADR 0030 lit ces segments depuis trois sources, vérifiées le 2026-11-07 (`docs/evidence/intros-generiques-2026-11-07.md`). Sur le serveur de test, aucun élément n'a de marqueur Emby : sans IntroDB ni TheIntroDB, le bouton n'apparaîtrait jamais.

## Décision
- **Mêmes sources et même ordre que sous Windows** (`src-tauri/src/segments`), dans `emby/Segments.kt` :
  - marqueurs Emby : `GET /Users/{userId}/Items/{itemId}` **sans `Fields`** (`EmbyClient.fullItem`), champs `Chapters[].MarkerType` (`IntroStart`, `IntroEnd`, `CreditsStart`) et `StartPositionTicks` ;
  - puis IntroDB (`GET https://api.introdb.app/segments?imdb_id=…&season=…&episode=…`, ou `&is_movie=true`), champs `intro`, `outro`, `no_intro`, `no_outro` ;
  - puis TheIntroDB (`GET https://api.theintrodb.org/v4/media?tvdb_id|tmdb_id|imdb_id=…&duration_ms=…&season=…&episode=…`), listes `intro` et `credits` de `{start_ms, end_ms}`.
  - Chaque type vient de la première source qui le donne ; une source n'est pas interrogée quand les deux types sont réglés.
- **Identifiants :** ceux du film, ou de la série (lue sans `Fields`) avec la saison et l'épisode. Clés `ProviderIds` sans tenir compte de la casse, validées avant l'URL (`tt` et 7 ou 8 chiffres, entiers de 1 à 100 000 000). Rien d'autre ne part : ni jeton, ni adresse du serveur, ni titre.
- **Client HTTP des services externes :** dérivé du client de l'application mais sans en-tête Emby (ajouté requête par requête, jamais ici), sans redirection, 3 s pour se connecter et 4 s par appel, TLS validé, réponse bornée à 64 Kio.
- **Cache :** en mémoire, par serveur, utilisateur et élément, au plus 256 entrées, pour la vie du processus. Seules les réponses définitives sont gardées (données, absence, 4xx sauf 429). Une coupure, un 429, un 5xx, une redirection ou une réponse illisible font réessayer à la lecture suivante.
- **Lecteur** (logique pure dans `player/Skip.kt`, reprise de `src/lib/player/segments.ts`) :
  - pendant l'intro, un bouton « Passer l'intro » en bas à droite, au-dessus du texte des commandes quand elles sont affichées, et qui reste quand elles s'effacent. Il prend le focus à son apparition et à la fermeture du menu des pistes, jamais pendant que le menu est ouvert. OK va à la fin de l'intro. Le bouton disparaît dans la dernière demi-seconde du segment ;
  - au début du générique d'un épisode qui a un suivant, la carte « Épisode suivant » de l'ADR 0043 s'ouvre, une fois par générique et par lecture, avec son compte à rebours. « Annuler » ou Retour ferment la carte et laissent le générique continuer ; il garde alors un bouton « Passer le générique ». À la fin du fichier, la carte revient comme avant ;
  - pour un film ou le dernier épisode, « Passer le générique » va à la fin du générique. Un générique sans fin, ou qui finit à moins de 2 s de la fin du fichier, mène à la fin du fichier : mpv l'atteint et le lecteur se ferme comme à une fin naturelle (rapport `Stopped` à la fin, donc élément vu) ;
  - sans segment, rien ne change.
  - Pendant le bouton, OK le presse ; la touche lecture / pause met toujours en pause.
- **Arrière-plan :** les segments arrivent après le titre, sans retarder la lecture. Une réponse d'un autre élément est ignorée, et les positions de l'ancien fichier ne comptent plus dès qu'un épisode en remplace un autre.
- **Journal :** seulement le type, les bornes et la source des segments (`ViewerTV`), jamais d'URL ni d'identifiant.

## Alternatives
- **Marqueurs Emby seuls :** aucun élément du serveur de test n'en a ; la fonction serait invisible. Écarté.
- **Réglages comme sous Windows** (sources externes désactivables, passage automatique) : l'application TV n'a pas d'écran de réglages. Les sources externes sont actives, comme le défaut choisi par le propriétaire le 2026-11-07 ; pas de passage automatique, qui est désactivé par défaut sous Windows. À ajouter avec un écran de réglages.
- **Bouton « Épisode suivant » à côté de « Passer le générique »** (Windows) : la carte existante avec son compte à rebours suffit à la télécommande et évite deux boutons à départager au focus.
- **Seconde requête avec `Fields=Chapters,ProviderIds`** : non vérifiée sur le serveur ; la lecture sans `Fields` l'est (preuve du 2026-11-07). Le titre du lecteur la réutilise, donc l'élément n'est lu qu'une fois.

## Conséquences
- Un épisode coûte une requête Emby de plus (la série) et au plus deux requêtes externes par lancement de l'application.
- Les identifiants IMDb, TMDB ou TVDB des titres regardés partent vers deux services tiers, qui voient l'IP de la Shield. Aucun réglage ne le coupe pour l'instant.
- Lancer l'épisode suivant depuis la carte au générique envoie le `Stopped` à la position atteinte : le serveur décide s'il marque l'épisode vu (seuil de fin de lecture d'Emby).
- Les données sont communautaires : un segment peut être faux ou viser une autre version du fichier.
