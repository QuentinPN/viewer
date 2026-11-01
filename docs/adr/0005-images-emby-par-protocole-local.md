# ADR 0005 — Images Emby servies par un protocole local géré par Rust

Statut : Accepté pour la Gate 2 (2026-10-27). Le cache disque des images est branché derrière ce protocole (ADR 0019).

## Contexte
La grille des bibliothèques affiche des centaines d'affiches. Les contraintes :
- l'UI ne construit pas d'URL vers le serveur et ne voit jamais le jeton (ADR 0004) ;
- aucun secret dans une URL ;
- un cache d'images borné et propre à chaque serveur viendra en Gate 3.

Observé sur le serveur réel (Emby 4.10.1.0) :
- la spec OpenAPI annote `GET /Items/{Id}/Images/{Type}` « requires auth », mais le serveur la sert **sans** authentification, avec `Cache-Control: public, max-age=31536000` quand `tag` est fourni ;
- une image absente (par exemple `Logo`) renvoie quand même **200** avec une image générique : la présence d'une image se lit dans `ImageTags`, pas dans le statut HTTP.

## Décision
- Rust enregistre un protocole `emby-img`, que WebView2 expose sous `http://emby-img.localhost/`.
- Les modèles envoyés à l'UI portent déjà l'adresse de l'image (`src` et `srcset`), construite par Rust à partir de l'id de l'élément et de son `ImageTags`. L'UI se contente de l'afficher.
- Le gestionnaire du protocole :
  - n'accepte que `/{itemId}/{Primary|Backdrop|Thumb|Logo}` avec `tag` et `maxWidth` (borné à 32–1920) ;
  - relaie la requête vers le serveur de la session ouverte, avec l'en-tête `X-Emby-Authorization` ;
  - limite à 6 téléchargements simultanés pour ménager le NAS ;
  - refuse les réponses qui ne sont pas des images ou qui dépassent 10 Mo ;
  - répond 401 sans session ouverte.
- La CSP n'autorise que `http://emby-img.localhost` en plus de `'self'`, `data:` et `blob:` pour `img-src`.

## Alternatives
- **`<img src>` direct vers le serveur** : fonctionne aujourd'hui sans jeton sur ce serveur, mais dépend d'un comportement contraire à la spec, oblige à ouvrir la CSP à l'adresse du serveur (qui change selon l'utilisateur) et ne laisse aucune place au cache. Écarté.
- **Jeton dans l'URL (`api_key=`)** : interdit par CLAUDE.md.
- **Commande Tauri renvoyant les octets en base64** : lourd en IPC pour des centaines d'images, sans cache du navigateur ni chargement paresseux natif. Écarté.

## Conséquences
- Le jeton ne quitte pas Rust, même pour les images.
- L'UI garde le chargement paresseux et `srcset` du navigateur.
- Un point unique pour brancher le cache disque, les quotas et les statistiques de cache.
- L'adresse `http://emby-img.localhost` est propre à WebView2 sous Windows ; une autre plateforme demanderait `emby-img://`.

## Validation
- Tests unitaires du parseur de chemin (types, bornes, caractères refusés).
- Captures de la grille sur le serveur réel, avec les affiches chargées.
