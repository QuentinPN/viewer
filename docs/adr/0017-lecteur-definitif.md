# ADR 0017 — Interface définitive du lecteur

Statut : Accepté (2026-10-28).

## Contexte
La lecture depuis Emby utilisait encore l'habillage du prototype de Gate 1 (`PlayerSpike.svelte`) : des badges techniques en haut, une barre de commandes toujours visible, des listes déroulantes natives pour l'audio et les sous-titres. L'utilisateur a validé le 2026-10-28 :
- **habillage** : « Habillage complet » :
  - titre (et série, saison, épisode) en haut ;
  - commandes masquées après 3 s sans mouvement ; la souris ou une touche les rappelle ; jamais masquées en pause ;
  - grande barre de progression avec le temps restant ;
  - recul et avance de 10 s ;
  - volume et sourdine (touche M) ;
  - panneaux « Audio » et « Sous-titres » ;
  - plein écran et retour ;
  - diagnostic derrière un bouton « i » ;
- **fin d'épisode** : « Proposition avec compte à rebours » (carte « Épisode suivant », lancement après 10 s). **Reporté** à une tranche suivante ; cette décision ne le couvre pas ;
- **mémoire** : « Volume retenu, pistes par Emby ». Le volume et la sourdine sont gardés sur ce PC ; les pistes audio et sous-titres restent celles que le serveur choisit à chaque lecture.

## Décision
- **Composant** : un nouveau `PlayerView.svelte` sert toute lecture lancée depuis Emby. `PlayerSpike.svelte` reste tel quel pour le prototype de Gate 1 (mode `prototype` et mode scénario), qui garde sa valeur de banc d'essai.
- **Titre** : l'interface ne construit ni URL ni requête. `emby_play` renvoie un `title` dans `PlaybackStart` :
  - `name` ;
  - pour un épisode : `seriesName`, `seasonIndex`, `index`, `indexEnd`.

  Rust lit l'élément (`/Users/{Id}/Items/{Id}`) **en parallèle** de `PlaybackInfo` (`tokio::join!`) : le lancement ne prend donc pas de requête de plus en temps. Cette lecture sert aussi au point de reprise, qui la faisait déjà.
  - Si la lecture de l'élément échoue sans reprise demandée, la lecture démarre quand même, sans titre (`title: null`). Avec reprise, l'échec reste bloquant, comme avant.
  - Un 401 met toujours fin à la session.
- **Masquage** :
  - les commandes et le curseur disparaissent après 3 s sans souris ni touche ;
  - ils restent visibles en pause, pendant la mise en mémoire tampon, quand un panneau est ouvert, pendant le glissement de la barre, et tant que le pointeur survole les commandes ;
  - le masquage est une opacité (fondu désactivé si l'utilisateur réduit les animations), sans retirer les éléments : le focus clavier reste en place.
- **Clavier** : on garde les touches existantes (Espace/K, ←/→ ±10 s, Maj ±30 s, F, Échap). On ajoute :
  - **M** : sourdine ;
  - **↑/↓** : volume ±5 % ;
  - **I** : diagnostic.

  Échap ferme d'abord un panneau ouvert, puis quitte le plein écran, puis revient à la page précédente.
- **Volume** : le moteur n'a pas de commande de sourdine. La sourdine envoie `setVolume 0` et garde le niveau précédent. Le niveau (0–100) et la sourdine sont gardés dans `localStorage` (clé indépendante du compte : c'est un réglage de ce PC) et appliqués à chaque `fileLoaded`. Si le stockage est refusé, rien n'est gardé et le volume part de 100.
- **Pistes** : panneaux à boutons radio (« Désactivés » en tête pour les sous-titres) au lieu des listes natives. Rien n'est mémorisé : la piste par défaut reste celle de la réponse du serveur.
- **Diagnostic** : le bouton « i » ouvre un panneau avec la décision du serveur, le décodage (matériel ou logiciel), le codec et la définition vidéo, le codec et la sortie audio, la sortie vidéo, les images perdues et la version du moteur. La décision du serveur (« Lecture directe · MKV », « Transcodage · … ») reste aussi en petit en haut à droite (`title="Décision du serveur Emby"`) : elle rassure et les scénarios E2E s'y appuient.

## Conséquences
- `PlaybackStart` grossit d'un champ ; le faux serveur couvre un film, un épisode et l'échec de lecture de l'élément.
- Une requête de plus par lancement sans reprise, faite en parallèle.
- Le volume n'est pas synchronisé avec Emby ni entre PC.
- Non couvert : la carte « Épisode suivant » (tranche suivante), les chapitres, la vitesse de lecture, le choix de format d'image.
