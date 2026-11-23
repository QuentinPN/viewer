# Viewer — Produit

## Ce que c'est

Un client Emby personnel, rapide et soigné, en deux applications :

- **Windows 10/11 x64** : client de bureau (fenêtre native, lecteur libmpv).
- **Android TV** : application pour la Shield TV, pilotée à la télécommande.

Le serveur Emby tourne sur un mini-PC ; les fichiers média sont sur un NAS QNAP. Les applications parlent à Emby, jamais au NAS. Emby reste la source de vérité : utilisateurs, bibliothèques, progression et états « vu » restent ceux du serveur. Elles parlent aussi à Request (« Demander ») et, si un compte Stremio est connecté (Réglages › Stremio), aux addons de ce compte, qui fournissent seulement des flux supplémentaires à lire : ni catalogue, ni synchronisation de la bibliothèque ou de la progression Stremio. Aucun backend à nous en plus.

Sur Android TV, l'icône et la bannière du lanceur, comme le logo affiché dans l'app, changent de tenue aux fêtes (calendrier de `contracts/seasons`). Le réglage « Icônes saisonnières » (activé par défaut, par appareil) les coupe ; le changement se fait à chaque lancement de l'app et tout de suite quand on bascule le réglage, sans redémarrer l'app.

## Pour qui

Un seul utilisateur : le propriétaire, sur son propre serveur. Pas de distribution, pas de public. Interface en français d'abord. Accès en réseau local et en HTTPS à distance.

Plusieurs comptes et plusieurs serveurs sont possibles, mais jamais au prix de la simplicité du cas courant.

## Principes, par ordre de priorité

1. **La lecture d'abord.** Une lecture qui démarre vite, reprend où elle s'est arrêtée et ne se coupe pas vaut plus que n'importe quelle fonction.
2. **Lecture directe quand c'est possible**, transcodage en repli. La décision vient du serveur (négociation de lecture), jamais de l'extension du fichier.
3. **Réactivité.** La navigation ne bloque jamais sur le réseau : squelettes, cache jetable, pagination.
4. **Secrets protégés.** Le jeton vit dans le stockage sécurisé du système, jamais dans un journal, une URL ou un fichier en clair. TLS validé.
5. **Honnêteté.** Les limites (codecs, HDR, passthrough audio) sont documentées et diagnostiquées, pas masquées. Une fonction n'est « finie » que vérifiée pour de vrai.
6. **Accessibilité.** Clavier complet, focus visible, mouvement réduit respecté, états de chargement, de vide et d'erreur toujours prévus.
7. **Maintenabilité.** Petites tranches verticales, peu de dépendances, aucune ajoutée sans nécessité.

## Logo saisonnier

Le raton laveur change de tenue selon la date (Noël, Halloween, Pâques, 14 juillet…). Sur le bureau, le logo de l'interface et l'icône de la fenêtre (barre de titre, bouton de la barre des tâches, Alt+Tab) suivent le calendrier local, à l'ouverture puis à chaque minuit, sans redémarrer. Réglages › Apparence › « Icônes saisonnières », activé par défaut : désactivé, le logo reste le logo par défaut. L'icône épinglée dans la barre des tâches et celle des raccourcis sont celles de l'exe (logo par défaut) : elles ne changent jamais. La build de débogage du bureau garde toujours le raton au casque de chantier (logo et icône de la fenêtre), comme celle d'Android ; son réglage est alors grisé.

## Hors périmètre

- Un serveur média, un moteur de transcodage, un contournement de DRM.
- Parler directement au NAS ou modifier la configuration du serveur Emby.
- Télécharger des médias ou les synchroniser hors ligne.
- Récupérer des métadonnées depuis des catalogues tiers (le serveur s'en charge ; les addons Stremio ne servent qu'à trouver des flux).
- Installer ou configurer des addons Stremio depuis l'app, lire des flux torrent sans lien direct.
- Distribuer l'application à d'autres personnes, la publier dans un magasin public.
- Télémétrie envoyée à l'extérieur.
- D'autres plateformes cibles : ni macOS, ni Linux, ni téléphone, ni autre télé que la Shield.
