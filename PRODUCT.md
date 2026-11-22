# Viewer — Produit

## Ce que c'est

Un client Emby personnel, rapide et soigné, en deux applications :

- **Windows 10/11 x64** : client de bureau (fenêtre native, lecteur libmpv).
- **Android TV** : application pour la Shield TV, pilotée à la télécommande.

Le serveur Emby tourne sur un mini-PC ; les fichiers média sont sur un NAS QNAP. Les applications ne parlent qu'à Emby, jamais au NAS. Emby reste la source de vérité : utilisateurs, bibliothèques, progression et états « vu » restent ceux du serveur. Aucun backend supplémentaire.

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

## Hors périmètre

- Un serveur média, un moteur de transcodage, un contournement de DRM.
- Parler directement au NAS ou modifier la configuration du serveur Emby.
- Télécharger des médias ou les synchroniser hors ligne.
- Récupérer des métadonnées depuis des catalogues tiers (le serveur s'en charge).
- Distribuer l'application à d'autres personnes, la publier dans un magasin public.
- Télémétrie envoyée à l'extérieur.
- D'autres plateformes cibles : ni macOS, ni Linux, ni téléphone, ni autre télé que la Shield.
