# ADR 0012 — Session persistante et reconnexion au serveur

Statut : Accepté (2026-10-28).

## Contexte
La Gate 2 conserve déjà la session (ADR 0004) : jeton dans le Gestionnaire d'identification Windows, métadonnées dans `session.json`, restauration au démarrage, jeton oublié quand le serveur répond 401. Mais tout échec de restauration ramenait au formulaire de connexion, et un serveur éteint, une session révoquée ou une simple erreur 500 se présentaient de la même façon. La Gate 2.1 demande :
- d'entrer automatiquement quand la session est valide, sans montrer le formulaire pendant la vérification ;
- des états de connexion explicites, et un écran de récupération quand le NAS est injoignable, sans jamais déconnecter sur une panne passagère ;
- de ne redemander le mot de passe que si le serveur rejette vraiment la session ;
- de changer de serveur sans détruire la session du précédent, et de pouvoir y revenir ;
- de retrouver la vue, la sélection et le défilement après une coupure, et de proposer une reprise sûre d'une lecture interrompue.

Points de départ constatés dans le code :
- `restore()` envoyait le jeton dans la sonde `/System/Info/Public`, **avant** de vérifier que l'identifiant du serveur était le bon : un autre serveur à la même adresse recevait le jeton.
- Une réponse 401 arrivée tard, d'une requête lancée avant une nouvelle connexion, effaçait la **nouvelle** session.
- Les URLs d'images `emby-img` (servies avec `immutable`) ne portaient pas l'identité du serveur.

## Décision

### États de connexion
L'interface suit une machine à états pure (`src/lib/connection/machine.ts`) :

| État | Écran | Sortie |
|---|---|---|
| `restoring` | écran de marque « Ouverture de votre session… » (jamais le formulaire) | réponse de `emby_restore` |
| `authenticated` | application | erreur réseau confirmée, 401, déconnexion, changement de serveur |
| `server_unreachable` | écran de récupération : adresse, explication, « Réessayer », « Changer de serveur » | tentative réussie, 401, changement de serveur |
| `authentication_required` | réauthentification : serveur et nom d'utilisateur préremplis, mot de passe demandé | connexion, changement de serveur |
| `server_configuration` | formulaire d'adresse, avec « Revenir à … » pour chaque session mise de côté | connexion, retour à une session |
| `signed_out` | formulaire d'adresse (premier lancement, après déconnexion) | connexion |

Les écrans `server_unreachable` et `authentication_required` se posent **au-dessus** de l'application quand ils surviennent en cours d'usage : l'arbre de navigation reste monté (`hidden` + `inert`), ce qui garde vue, sélection, fiche ouverte et défilement. La navigation n'est démontée qu'au changement de serveur ou d'utilisateur.

### Classement des erreurs (Rust)
`emby_restore` ne renvoie plus `Option<User>` mais un `SessionStatus` typé : `signedOut`, `authenticated`, `serverUnreachable { server, error }`, `authenticationRequired { server, error }`.
1. La sonde publique part **sans jeton**. Si l'identifiant du serveur diffère de celui de la session : `serverMismatch` (nouvelle catégorie), état `serverUnreachable`, jeton conservé et jamais envoyé.
2. `GET /Users/{Id}` avec le jeton valide la session :
   - 401 → `sessionExpired` ;
   - 403, 404, ou `Policy.IsDisabled` → `accountUnavailable` (nouvelle catégorie : compte désactivé ou supprimé) ;
   - dans ces deux cas seulement, le jeton de **ce** couple serveur/utilisateur est supprimé du coffre.
3. Toute autre erreur (injoignable, délai dépassé, DNS, 5xx, réponse illisible) → `serverUnreachable`, rien n'est effacé.

En cours de session, seul un 401 met fin à la session. Un 403 ou un 404 sur un élément reste une erreur locale. Une erreur `unreachable` ou `timeout` d'une vue déclenche **une** vérification (`emby_restore`), et c'est son résultat qui décide de l'écran de récupération : un échec isolé reste affiché dans la vue avec son bouton « Réessayer ».

### Une seule transition, pas de réponse périmée
- Rust tient une **époque** de session, incrémentée à chaque connexion, déconnexion, changement de serveur ou retour à une session. Une restauration ne s'installe que si l'époque n'a pas bougé pendant ses requêtes ; sinon elle renvoie `cancelled`. Un clic sur « Changer de serveur » pendant une vérification l'annule donc réellement.
- Un 401 n'efface la session que si le jeton refusé est celui de la session courante. Sinon la requête venait d'une session précédente : elle renvoie `cancelled`, que l'interface ignore.
- Côté interface, le coordinateur (`src/lib/connection/coordinator.ts`) n'a qu'une vérification en vol, et ignore une réponse dont la génération est dépassée. Plusieurs 401 simultanés (les rangées de l'accueil partent en parallèle) produisent une seule transition vers `authentication_required`.
- `session.json` n'est modifié que si son enregistrement est toujours celui de la session concernée.

### Nouvelles tentatives
- Délais par requête inchangés : connexion 5 s, requête 15 s.
- Pas de nouvelle tentative par requête : avec un NAS éteint, chacune coûterait 5 s de plus et multiplierait le temps avant l'écran de récupération.
- Les nouvelles tentatives sont centralisées dans le coordinateur, sur la seule opération de validation (`GET /System/Info/Public` puis `GET /Users/{Id}`, toutes deux idempotentes) :
  - backoff exponentiel 2 s, 4 s, 8 s, 16 s, 30 s, 30 s, avec un jitter aléatoire de 50 à 100 % du délai ;
  - **6 tentatives automatiques au plus**, puis l'écran l'indique et attend « Réessayer » ;
  - « Réessayer » tente tout de suite et repart de la première marche ;
  - l'événement `online` de la WebView déclenche une tentative immédiate ;
  - un compte à rebours accessible (`aria-live="polite"`) annonce la prochaine tentative.

### Changement de serveur
- `emby_change_server` incrémente l'époque, ferme la session en mémoire **sans** révoquer ni effacer le jeton, et range l'enregistrement dans `session.json` (`saved`, 8 au plus). Au-delà, le plus ancien est oublié et son jeton supprimé du coffre.
- `emby_resume_saved(serverId, userId)` remet une session rangée au premier plan, puis la valide comme au démarrage.
- Une connexion réussie retire du rangement le même couple serveur/utilisateur.
- La déconnexion volontaire garde le comportement existant : révocation sur le serveur si joignable, jeton et enregistrement supprimés. Elle ne touche pas aux sessions rangées.
- Un rejet laisse dans `session.json` un indice non secret (`reauth` : adresse, nom du serveur, nom d'utilisateur) pour préremplir la réauthentification, même après redémarrage. Il est effacé par une connexion ou un changement de serveur.

### Caches
- Le seul cache persistant aujourd'hui est celui de la WebView pour `emby-img`. Les URLs deviennent `http://emby-img.localhost/{ServerId}/{ItemId}/{Type}?tag=…&maxWidth=…`, et Rust refuse (403) une image d'un autre serveur que celui de la session.
- Les images Emby ne dépendent pas de l'utilisateur : la portée serveur suffit.
- La liste des bibliothèques en mémoire vit dans la session et disparaît avec elle.

### Retour de la connexion et lecture interrompue
- Au retour de `server_unreachable` vers `authenticated`, un compteur `refresh` est incrémenté. Les vues relancent ce qui était en erreur : bibliothèques, page de la grille, recherche, rangées de l'accueil, fiche. Elles ne se remettent pas à zéro.
- Une lecture qui se termine en erreur, ou par une fin de fichier à plus de 30 s de la durée connue, est traitée comme **interrompue** :
  - le rapport d'arrêt part avec `Failed=true` et la dernière position atteinte par le lecteur, jamais la durée totale ;
  - l'interface ferme le lecteur, lance une vérification de connexion, puis affiche « Lecture interrompue » avec « Reprendre ». Ce bouton relance `emby_play(itemId, resume=true)`, qui démarre à la dernière position **enregistrée par le serveur**.
  - aucun « vu » n'est envoyé (`PlayedItems` n'est jamais appelé par la lecture).
- La vérification après une lecture interrompue passe par `coordinator.verify()`, qui ne fait rien hors de l'état `authenticated`.
- **mpv et `keep-open=yes`** : quand le flux s'arrête, mpv n'émet pas `end-file`. Il reste en pause sur la dernière image, après avoir lu son cache. Constaté le 2026-10-28 : environ 3 minutes lues après la coupure, puis plus aucun événement. Le moteur observe donc aussi `eof-reached` et émet `EndFile { reason: Eof }` quand elle passe à vrai. La règle des 30 s ci-dessus s'applique ensuite.
- Limite mesurée : tant que le cache de mpv contient des données, la lecture continue et la coupure n'est remarquée qu'à son épuisement (plus de 90 s dans le scénario). Accepté pour l'instant ; un réglage de `network-timeout` ou du cache est une piste.
- Le focus revient au dernier élément focalisé dans l'application quand un écran de récupération ou de reconnexion se retire.

## Alternatives
- **Nouvelles tentatives dans `EmbyClient` pour chaque GET.** Écartée : elles retardent l'écran de récupération de plusieurs fois 5 s quand le NAS est éteint, et multiplient la charge sur un serveur déjà en difficulté.
- **Machine à états en Rust, poussée par événements.** Possible, mais les comptes à rebours, le focus et la navigation sont côté interface. Rust garde ce qui touche au jeton : le classement des erreurs, l'époque et le coffre. L'interface garde les transitions et les minuteries, testées par Vitest.
- **Garder plusieurs sessions actives en même temps.** C'est du multi-serveur (P2 dans `PRODUCT.md`). Ici, une seule session est active, et les autres sont rangées.
- **`no-store` sur les images au lieu de les préfixer par le serveur.** Écarté : on perdrait le cache HTTP de la WebView.

## Conséquences
- `emby_restore` change de type de retour, et `emby_last_server` reste pour préremplir l'adresse.
- Nouvelles commandes : `emby_change_server`, `emby_saved_sessions`, `emby_resume_saved`.
- Nouvelles catégories d'erreur : `accountUnavailable`, `serverMismatch`, `cancelled`.
- Une session rangée garde un jeton valide côté serveur tant que l'utilisateur n'y revient pas pour se déconnecter. C'est voulu (« revenir au serveur précédent »), et borné à 8.
- Le comportement de mpv face à une coupure réseau dépend de son cache et de `network-timeout` : il est mesuré, pas supposé (voir Validation).

## Validation
Voir `docs/evidence/gate2-1-session-2026-10-28.md`.
