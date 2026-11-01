# ADR 0021 — Écran Réglages : taille et vidage du cache des images

Statut : Accepté (2026-11-01). Complète l'ADR 0019.

## Contexte
Le cache disque des images (ADR 0019) est borné à 512 Mio, mais l'utilisateur ne voit ni sa taille ni comment le vider. DESIGN.md prévoit un écran Réglages (connexion, lecture, interface, cache, diagnostics) et PRODUCT.md la « gestion du cache ». La commande `artwork_cache_stats` existe déjà, en lecture seule.

## Décision
- **Un écran « Réglages »**, ouvert depuis la barre latérale (au-dessus des boutons du compte), à la place du contenu principal comme l'accueil ou une bibliothèque. La recherche reste prioritaire : taper dans le champ montre les résultats, et vider le champ ramène aux réglages. Cette tranche n'y met que la section « Cache des images » ; les autres sections viendront quand elles auront un comportement réel.
- **Ce qui est affiché** : la place prise et le quota (« 123 Mo sur 512 Mo »), le nombre d'images, et depuis le lancement le nombre d'images lues sur le disque et demandées au serveur.
- **Vider le cache** : une commande `artwork_cache_clear`.
  - Elle supprime toutes les images de tous les serveurs : le quota est commun, et une image ne contient rien de propre à l'utilisateur.
  - Elle ne supprime que les fichiers `.img` du cache, puis les dossiers devenus vides. Un fichier inconnu dans le dossier reste en place.
  - Le verrou de taille est tenu pendant le vidage, et la taille est relue sur le disque à l'écriture suivante.
  - Elle rend le nombre d'images et d'octets supprimés, qui sont annoncés (« Cache vidé : 12 images, 3,4 Mo libérés. »). Le vidage est noté au niveau `info`, sans chemin ni serveur.
  - Elle ne demande pas de session ; elle rend `null` si l'application n'a pas de dossier de cache.
- **Pas de confirmation** : ce sont des données locales jetables, retéléchargées à l'affichage suivant. Rien n'est modifié sur le serveur.

## Alternatives
- **Boîte de confirmation** : protège contre un clic accidentel, mais le seul coût d'une erreur est de retélécharger des images. Rejetée.
- **Vider seulement le serveur courant** : le quota est global, et un serveur abandonné garderait sa place. Rejetée.
- **Régler le quota** : demande un stockage des préférences et une éviction immédiate. Reporté.

## Conséquences
- Les images déjà affichées peuvent rester dans la mémoire et le cache HTTP du WebView (réponses `immutable`, ADR 0005) jusqu'au prochain lancement. Le cache disque, lui, est vide.
- Une image en cours de téléchargement pendant le vidage peut être écrite juste après : elle est comptée à l'écriture suivante.
