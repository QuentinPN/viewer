# ADR 0019 — Cache disque des images Emby

Statut : Accepté (2026-11-01). Complète l'ADR 0005. Le vidage depuis l'écran Réglages est décrit par l'ADR 0021.

## Contexte
Chaque grille, la page d'accueil, le héros et les fiches affichent des dizaines à des centaines d'images. Aujourd'hui, toutes passent par le protocole `emby-img` (ADR 0005), qui les redemande au NAS à chaque lancement de l'application : au plus 6 en parallèle, et rien sans le serveur.

L'architecture prévoit un cache local jetable, propre à chaque serveur et borné (ARCHITECTURE, CLAUDE.md). L'URL d'une image porte son `tag` Emby. Quand l'image change sur le serveur, son tag change : une adresse donnée renvoie donc toujours la même image (ADR 0005, `immutable`).

## Décision
- **Emplacement.** Le cache est un dossier de fichiers dans le cache de l'application : `%LOCALAPPDATA%\io.github.quentinpn.viewer\artwork\{serverId}\{itemId}\{Type}-{largeur}-{tag}.img`.
  - La clé de l'image suffit à la décrire entièrement. Il n'y a donc pas de base de métadonnées : la taille se lit sur le disque, et la dernière utilisation est la date de modification du fichier.
  - Le type MIME se reconnaît aux premiers octets (JPEG, PNG, WebP, GIF). Un fichier non reconnu n'est ni servi ni gardé.
- **Ordre de traitement d'une requête.**
  1. Le contrôle de la session et du serveur (ADR 0012) passe toujours en premier : sans session, ou avec l'image d'un autre serveur, il n'y a ni lecture du cache ni réponse.
  2. Ensuite vient le cache. Une image trouvée est servie sans attendre une des 6 places réservées au NAS, et même si le NAS ne répond plus pendant une session ouverte.
  3. Sinon, l'image est téléchargée comme avant, puis écrite dans le cache.
- **Écriture.**
  - Le fichier est écrit sous un nom temporaire, puis renommé : une lecture ne voit jamais un fichier à moitié écrit.
  - Une image de plus d'un quart du quota n'est pas gardée.
  - Une erreur du cache est notée au niveau `debug` (sans URL du serveur ni jeton) et ne fait jamais échouer l'image.
- **Quota.**
  - 512 Mio par défaut, tous serveurs confondus.
  - Au-delà, les fichiers les moins récemment utilisés sont supprimés jusqu'à revenir à 80 % du quota ; les dossiers vides et les fichiers temporaires abandonnés depuis plus d'une heure le sont aussi.
  - Une lecture met à jour la date du fichier au plus une fois par heure, pour ménager le disque.
- **Ce que le cache ne contient pas.** Il n'y a ni jeton, ni adresse du serveur, ni utilisateur. Les images ne dépendent pas de l'utilisateur, seulement du serveur. La déconnexion garde le cache : il est jetable et le quota le borne.
- **Statistiques.** Une commande `artwork_cache_stats` en lecture seule rend le nombre de fichiers, les octets, le quota, ainsi que les lectures trouvées et manquées depuis le lancement. Elle sert aux scénarios E2E aujourd'hui, et à l'écran Réglages (« Vider le cache ») plus tard.

## Alternatives
- **SQLite pour l'index** : utile pour le cache des métadonnées à venir. Pour des images dont la clé dit tout, il ajouterait une dépendance et une seconde source de vérité à garder en accord avec le disque. Reporté au cache des métadonnées.
- **Le cache HTTP de WebView2 seul** : il n'est ni borné ni rangé par serveur par nos soins. Sa tenue pour un protocole personnalisé n'est pas garantie, et il échappe aux mesures. Écarté comme seule solution ; les en-têtes `immutable` restent.
- **Nom de fichier haché** : la bibliothèque standard ne garantit pas la stabilité de son hachage d'une version à l'autre, et une collision servirait une mauvaise image. Les composants de la clé sont déjà validés (lettres, chiffres, `-`, `_`), on les garde tels quels.

## Conséquences
- Au deuxième lancement, les images déjà vues ne sollicitent plus le NAS.
- Pendant une coupure du serveur, la session restant ouverte, les images déjà vues restent affichées.
- Jusqu'à 512 Mio sur le disque de l'utilisateur ; il n'y a pas encore de bouton pour vider le cache (écran Réglages, plus tard).
- Une image remplacée sur le serveur arrive avec un nouveau tag, donc un nouveau fichier ; l'ancien finit supprimé par le quota.

## Validation
- **Tests Rust (faux serveur)** :
  - la seconde demande d'une image ne touche plus le serveur ;
  - le contrôle de la session et du serveur passe avant le cache ;
  - les fichiers non reconnus ne sont ni servis ni gardés ;
  - le quota supprime les plus anciens ;
  - aucune trace du serveur dans le cache.
- **E2E sur le vrai serveur** : premier lancement avec des téléchargements, puis relance avec des lectures trouvées et aucun téléchargement pour les mêmes images.
