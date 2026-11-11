# ADR 0103 — Écran de pré-connexion « L'étagère » (desktop)

Statut : Accepté (2026-11-11).

## Contexte
Hors session, le desktop ouvrait sur une carte en deux étapes (ADR 0012, 0031) : l'adresse du serveur et « Continuer », puis le nom d'utilisateur et le mot de passe. Les sessions mises de côté apparaissaient en dessous, dans une liste « Sessions conservées » avec des boutons « Revenir à … ». Une déconnexion volontaire effaçait tout : il fallait retaper l'adresse et le nom d'utilisateur.

Le propriétaire a comparé trois maquettes et retenu la variante B, « L'étagère » : une rangée de grandes vignettes, une par serveur enregistré, comme des profils. La détection automatique de serveurs sur le réseau, présente dans les maquettes, a été abandonnée : elle n'existe pas dans cette tranche, ni dans le code ni dans l'interface.

## Décision

### Écran
- **Étagère** : grand logo (96 px), « Viewer » et un sous-titre :
  - « Qui regarde ? Choisissez un serveur. » quand des serveurs sont enregistrés ;
  - « Bienvenue. Ajoutez votre premier serveur Emby. » quand il n'y en a aucun ;
  - « Choisissez le serveur à oublier. » en mode « Gérer ».
- **Vignettes** : une par couple serveur + utilisateur, la plus récente d'abord.
  - Chaque vignette a un visuel 16:10 avec l'initiale du serveur, sur un dégradé dont la teinte vient d'un hachage de l'id du serveur. La couleur reste donc la même d'un lancement à l'autre.
  - Sous le visuel : le nom du serveur, puis « utilisateur · hôte ».
  - La pastille « Mot de passe requis » n'apparaît que si le serveur a déjà refusé cette session.
- **Sans serveur** : une vignette fantôme « Aucun serveur pour l'instant » (bordure pointillée, `aria-hidden`), avec « Se connecter manuellement » en bouton principal.
- **Sous l'étagère** : seulement « Se connecter manuellement » et « Gérer ». En mode « Gérer », la barre n'affiche plus que « Terminer ».
- Le serveur de la session ouverte n'est jamais listé.

### Choisir une vignette (clic ou Entrée)
- **Vignette avec jeton** : la session est revalidée par le parcours de restauration de l'ADR 0012 (`emby_resume_saved` puis `restore()`), derrière l'écran « Ouverture de votre session… ». Trois issues :
  - le jeton est valide : la session s'ouvre ;
  - le serveur refuse le jeton : la carte « Session expirée » s'ouvre, avec le nom d'utilisateur prérempli et le focus sur le mot de passe ;
  - le serveur est injoignable : l'écran « Serveur injoignable » existant s'affiche.
- **Vignette sans jeton** (déconnexion volontaire ou refus déjà constaté) : la carte du mot de passe s'ouvre aussitôt, sans requête réseau.
  - Après un refus, la carte dit « Session expirée » et « Le serveur « X » demande à nouveau votre mot de passe. ».
  - Sinon, elle dit « Connexion à X ».

### Gérer
- En mode « Gérer », chaque vignette porte « Oublier ».
- Choisir une vignette ouvre une confirmation « Oublier « X » ? » qui précise : « L'adresse, le nom d'utilisateur et la session enregistrés sur cet appareil seront effacés. Rien n'est supprimé sur le serveur. »
  - « Annuler » a le focus par défaut.
  - « Oublier ce serveur » est dessiné en rouge (danger).
- **Oublier** (`emby_forget_server`) se fait en deux temps :
  1. Le jeton est effacé du Gestionnaire d'identification Windows. Si cet effacement échoue, l'erreur est affichée et rien d'autre ne change.
  2. L'enregistrement est retiré de `session.json` (session, indice de réauthentification et liste des sessions mises de côté). `last_server_url` est vidé si plus aucun enregistrement n'utilise cette adresse.
- Ensuite, s'il y avait un jeton, l'app envoie `POST /Sessions/Logout` en tâche de fond, au mieux. Cet envoi :
  - suit la règle de l'ADR 0012 : la sonde `/System/Info/Public` part d'abord sans jeton, et le jeton n'est envoyé que si l'id du serveur correspond ;
  - ne bloque jamais le retrait, et ses échecs sont seulement journalisés par leur type d'erreur, sans jeton ni adresse.
  Rien n'est supprimé sur le serveur.

### Connexion manuelle
- Une seule carte : le logo, « Connexion manuelle », puis l'adresse, le nom d'utilisateur et le mot de passe, avec « Retour » et « Se connecter ».
- L'adresse est préremplie avec la dernière adresse utilisée.
- À l'envoi, l'adresse est sondée (`emby_probe`, validation inchangée), puis le compte se connecte. Les messages d'erreur sont ceux qui existaient déjà. Le focus revient :
  - à l'adresse pour une erreur d'adresse ;
  - au mot de passe pour des identifiants refusés.
- Un avertissement « Connexion non chiffrée (HTTP) » s'affiche pour une adresse http, ou une adresse sans schéma.
- Une note rappelle que le mot de passe n'est pas enregistré : seul le jeton est conservé, dans le Gestionnaire d'identification Windows. Le jeton n'apparaît dans aucune URL ni aucun journal.
- Les cartes « Connexion manuelle » et mot de passe s'ouvrent par-dessus l'étagère, sur un voile flouté. L'étagère passe alors en `inert`.
- La carte de réauthentification affichée par l'application (état `authentication_required`) garde « Changer de serveur » quand l'application est derrière elle. Sans application derrière, au démarrage, elle propose « Retour » vers l'étagère. Les deux appellent `emby_change_server`, qui range la session refusée sur l'étagère avec sa pastille.

### Modèle de session (`session.rs`)
- `SessionRecord` gagne `rejected` (absent du fichier quand il vaut faux).
- L'étagère (`emby_shelf`) liste dans cet ordre, sans doublon pour un même serveur + utilisateur (le premier trouvé l'emporte) :
  1. la session du fichier quand elle n'est pas ouverte ;
  2. l'indice de réauthentification, marqué refusé ;
  3. les sessions mises de côté.
  L'accès de chaque vignette vaut `session` si un jeton existe dans le coffre, `rejected` si le serveur l'a refusée, `signedOut` sinon.
- **Changement à l'ADR 0012** : une déconnexion volontaire révoque et efface toujours le jeton, mais garde l'enregistrement non secret (adresse, nom d'utilisateur) sur l'étagère.
- Le changement de serveur et la reprise d'une session rangent l'indice de réauthentification sur l'étagère avec la marque « refusé ». Une nouvelle connexion efface cette marque.
- La limite de huit sessions conservées (`MAX_SAVED`) s'applique toujours. Le jeton d'une session évincée est effacé.
- La commande `emby_saved_sessions` est remplacée par `emby_shelf`.
- Nouvelle commande `emby_forget_server { serverId, userId }`, typée et minimale. Elle renvoie `notFound` pour un compte inconnu.

### Clavier et mouvement
- Les flèches passent d'une vignette à l'autre, sans boucler. Le nombre de colonnes est déduit de la position des vignettes affichées. Début et Fin vont aux extrémités.
- Une vignette qui a le focus se soulève (×1,06, `--ease-spring`, `--shadow-lift`), avec un anneau d'accent.
- Échap ferme la carte ou la confirmation ouverte, puis quitte le mode « Gérer ». Le focus revient à la vignette ou au bouton d'origine.
- Avec le mouvement réduit, `--lift-scale` vaut 1 et `--motion-lift` vaut 0 : la vignette n'est pas agrandie et le changement n'est pas animé.

## Alternatives
- **Variante A (liste dans une carte)** et **variante C (deux colonnes)** : non retenues par le propriétaire.
- **Détection automatique sur le réseau local** (diffusion UDP d'Emby) : abandonnée par le propriétaire. Aucun code de découverte n'est ajouté.
- **Effacer l'enregistrement à la déconnexion, comme avant** : l'étagère serait vide après chaque déconnexion. Rejeté.
- **Révoquer avant de retirer, en attendant la réponse du serveur** : un serveur éteint bloquerait l'oubli. Rejeté : la révocation suit le retrait, en tâche de fond.

## Conséquences
- `session.json` peut contenir des enregistrements sans jeton : adresse, nom d'utilisateur et nom du serveur. Ce ne sont pas des secrets, et « Gérer » permet de les oublier.
- Les scénarios CDP passent par « Se connecter manuellement ». Les aides `openManualForm`, `signIn` et `shelfTile` de `scripts/e2e/scenario.mjs` sont adaptées, et `gate21-session` choisit la vignette de la session mise de côté au lieu de « Revenir à … ».

## Non vérifié
- Aucun lancement de l'app dans cette tranche. Le rendu, le soulèvement, le voile, le retour du focus et les scénarios CDP mis à jour n'ont été ni vus à l'écran ni rejoués.
- La révocation `POST /Sessions/Logout` à l'oubli est couverte par le serveur simulé, pas par le serveur réel.
- Lecteurs d'écran non testés.

## Vérification
- Rust : tests `shelf_lists_set_aside_signed_out_and_rejected_sessions_newest_first`, `forget_server_removes_the_record_and_token_then_revokes_best_effort`, `forget_server_never_sends_the_token_to_another_server` et `forget_server_without_token_only_removes_the_record`. Ils utilisent `MemoryCredentialStore` et le serveur simulé.
- Vitest : `src/lib/connection/shelf.test.ts` couvre l'identité, les libellés et l'action des vignettes, l'hôte affiché, l'avertissement HTTP, les déplacements au clavier et le nombre de colonnes.
- `npm run -s verify:web` et `npm run -s verify:rust` passent.
