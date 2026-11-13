# ADR 0119 — « Connexion perdue » et « Informations techniques » sur Android TV

Statut : Accepté (2026-11-13). Porte sur la TV le comportement desktop de l'ADR 0012 ; complète l'ADR 0104.

## Contexte
Sur le desktop, un serveur injoignable n'éjecte pas l'utilisateur : `ConnectionLost.svelte` reste affiché, la session est gardée, des tentatives automatiques repartent avec un délai croissant (`machine.ts`, `coordinator.ts`). Sur la TV, une session ouverte au démarrage alors que le serveur dort donnait un accueil vide avec le message d'erreur et un seul « Réessayer » manuel ; une coupure en cours d'usage n'était pas détectée hors de l'accueil.

## Décision
- Logique pure dans `session/ConnectionLoss.kt` (tests JVM) : même courbe que le desktop (2, 4, 8, 16, 30, 30 s, gigue 50–100 %), 6 tentatives automatiques puis arrêt jusqu'à « Réessayer », qui remet le compteur à zéro. Seules les erreurs transitoires (`Unreachable`, HTTP 5xx, `isTransient`) déclenchent l'écran.
- Vérification : sonde publique puis `GET /Users/{Id}` avec le jeton (`checkSession`). Un 401 termine la session comme avant (« Session expirée », ADR 0104) ; tout le reste garde la session.
- Déclencheur : un échec de lecture de l'accueil (premier chargement ou rafraîchissement) signale l'erreur ; une vérification discrète a lieu d'abord, l'écran n'apparaît que si elle échoue aussi (pas de clignotement sur une coupure d'une seconde).
- Écran plein (`ui/ConnectionLost.kt`) : logo, « Serveur injoignable », nom du serveur, hôte et utilisateur, l'erreur, l'état (« Nouvelle tentative dans N s… », « Vérification… », « Tentatives automatiques suspendues… »), boutons « Réessayer » (focus initial) et « Changer de serveur » (session mise de côté, jeton gardé, comme le menu du compte).
- Les états de navigation (pages, bibliothèque, recherche, mémoire de l'accueil) sont déclarés au-dessus de l'écran : au retour de la connexion l'utilisateur retrouve la page où il était, l'accueil est relu.

## Limites
- Seul l'accueil signale les erreurs réseau ; une page de détail qui échoue garde son propre message. Le lecteur a sa propre gestion d'erreur (inchangée).
- Pas d'écoute de `ConnectivityManager` (l'événement `online` du desktop) : les tentatives automatiques et « Réessayer » suffisent pour un serveur de salon.
- Non vérifié sur la Shield (serveur réellement coupé) : seuls les tests JVM et la compilation sont passés.

## Informations techniques (pages film et épisode)
- Le desktop affiche une section « Informations techniques » en bas de la fiche. Sur la TV, la colonne de gauche est déjà pleine : un bouton « Informations techniques » rejoint la rangée Vu/Favori et ouvre un panneau vitré à droite (Retour le ferme).
- Le panneau décrit la version que la page lira (`shownSource` : celle choisie avec le bouton « Version », sinon la première du serveur) : Vidéo (définition, codec, profondeur, HDR/Dolby Vision), Fichier (conteneur, taille, débit), Audio (langue, codec, canaux, titre, « par défaut ») et Sous-titres (langue, format, « forcés », « externe »). Mêmes libellés que `format.ts` du desktop.
- Chaque ligne est focusable pour que la croix fasse défiler une longue liste de pistes.
- Les champs `Container`, `Language`, `Title`, `Channels`, `IsDefault`, `IsForced`, `IsExternal` sont lus en plus dans `MediaSources` (déjà renvoyées par la fiche sans `Fields`). Non vérifié sur la Shield ni sur le vrai serveur.
