# ADR 0104 — Écran de pré-connexion « L'étagère » sur Android TV

Statut : Accepté (2026-11-11). Complète l'ADR 0012 (connexion) sur Android TV.

## Contexte
L'écran de connexion Android TV ne gardait qu'une session : une adresse, un utilisateur et un jeton. Changer de serveur ou de compte demandait de se déconnecter, puis de retaper l'adresse et le mot de passe. Le propriétaire a choisi la variante B « L'étagère » de la maquette de pré-connexion : une tuile par serveur enregistré, comme un écran « Qui regarde ? ». La détection automatique de serveurs (UDP) a été abandonnée par le propriétaire et n'est pas implémentée.

## Décision

### Écran
- En haut : grand logo, « Viewer » et un sous-titre (« Qui regarde ? Choisissez un serveur. », ou « Bienvenue. Ajoutez votre premier serveur Emby. » sans serveur).
- L'étagère : une tuile 16:10 par serveur enregistré, triée de la plus récente à la plus ancienne. Sous la tuile : le nom du serveur, puis « utilisateur · hôte ». La pastille « Mot de passe requis » apparaît quand le jeton manque ou a été refusé.
- Sans serveur enregistré : une tuile fantôme « Aucun serveur pour l'instant », et « Se connecter manuellement » est le bouton principal, qui prend le focus.
- Sous l'étagère, deux boutons seulement : « Se connecter manuellement » et « Gérer ». Pas de détection automatique.
- Navigation à la télécommande :
  - ◀ ▶ passent d'une tuile à l'autre et ▼ descend aux boutons.
  - Le focus agrandit l'élément et l'entoure d'un anneau, comme les autres composants TV, avec une animation réduite si le système le demande.
  - Retour remonte d'un niveau.

### OK sur une tuile
Le jeton enregistré est validé selon le flux de l'ADR 0012 :
1. Une sonde `GET /System/Info/Public` est envoyée sans jeton.
2. L'identifiant du serveur est vérifié. Si un autre serveur répond à l'adresse, le jeton n'est jamais envoyé, et l'erreur `ServerMismatch` s'affiche (« Un autre serveur répond à cette adresse… »).
3. `GET /Users/{id}` est envoyé avec le jeton dans l'en-tête `X-Emby-Authorization`, jamais dans l'URL.

Résultats :
- **Succès :** la session s'ouvre. La tuile remonte en tête et apprend le nom et l'identifiant du serveur.
- **401 :** seul ce code signifie que le jeton est refusé. Le jeton est supprimé et la carte « Session expirée » s'ouvre : le serveur est en lecture seule, le nom d'utilisateur est prérempli et le focus est sur le mot de passe.
- **Autre erreur (injoignable, 5xx…) :** une carte affiche « Serveur injoignable » ou « Connexion impossible » avec le message français de `EmbyError`, et les boutons « Retour » / « Réessayer » (le focus est sur « Réessayer »). Le jeton est conservé.
- **Tuile sans jeton :** la carte « Session expirée » s'ouvre directement.

### Mode « Gérer »
- Les tuiles affichent « Oublier » et la barre affiche « Terminer ». Retour quitte le mode.
- Choisir une tuile ouvre la confirmation « Oublier « X » ? ». Les boutons sont « Annuler » (focus par défaut) et « Oublier ce serveur ».
- Oublier supprime l'adresse, l'utilisateur et le jeton du stockage. Ensuite, un `POST /Sessions/Logout` est envoyé avec ce jeton, au mieux et sans être attendu. S'il échoue, il est ignoré. Rien d'autre n'est supprimé sur le serveur.

### Connexion manuelle
La carte affiche le logo, « Connexion manuelle », puis les champs adresse, nom d'utilisateur et mot de passe, avec les boutons « Retour » et « Se connecter ». Elle reprend la logique et les messages d'erreur de l'ancien écran de connexion (sonde publique, puis `AuthenticateByName`). L'adresse est préremplie avec le dernier serveur utilisé.

### Stockage et migration
- Les `SharedPreferences` « session » contiennent maintenant :
  - `servers` : une liste JSON (kotlinx.serialization) d'entrées. Chaque entrée contient l'URL du serveur, l'identifiant et le nom du serveur s'ils sont connus, l'identifiant et le nom de l'utilisateur, la date de dernière utilisation, et le jeton chiffré.
  - `active` : la clé `serveur|utilisateur` de la session ouverte.
- Chaque jeton est chiffré à part avec la clé AES-GCM existante du Keystore Android (`viewer.session`, format `iv.ct`). Le mot de passe n'est jamais enregistré. Le jeton n'est jamais journalisé (`toString` le masque).
- Un même utilisateur sur la même adresse, ou sur le même identifiant de serveur, remplace sa tuile. Un autre utilisateur du même serveur a sa propre tuile.
- La liste est limitée à 8 serveurs, et l'entrée la plus ancienne sort. Le jeton d'une entrée sortie ainsi n'est pas révoqué sur le serveur.
- **Migration :**
  - Elle se fait au premier démarrage, une seule fois : la présence de `servers` montre qu'elle est faite.
  - L'ancienne session (`server`, `userId`, `userName`, `token`) devient une entrée.
  - Le jeton chiffré est copié tel quel, avec la même clé et le même format. La session s'ouvre donc automatiquement, sans redemander le mot de passe.
  - Les anciennes clés `userId`, `userName` et `token` sont supprimées. `server` reste comme adresse proposée. `deviceId` ne change pas.
  - Le nom du serveur est appris au démarrage suivant de la session.
- Un jeton qui ne peut pas être déchiffré (clé du Keystore perdue) est retiré de son entrée. La tuile reste et demande le mot de passe.

### Démarrage et sortie de session
- Au démarrage, une session active et valide ouvre directement l'accueil. L'étagère s'affiche au premier lancement, après une déconnexion ou sur « Changer de serveur ».
- « Se déconnecter » garde la tuile, sans son jeton.
- « Changer de serveur » est une nouvelle entrée du menu du compte, dans la barre d'accueil. Elle ferme la session sans supprimer son jeton. Sans elle, l'étagère ne pourrait jamais garder plusieurs jetons, car la seule façon d'y revenir effacerait le jeton.
- Une session refusée pendant l'utilisation (401) ouvre l'étagère directement sur la carte « Session expirée » de sa tuile.

## Conséquences
- Plusieurs serveurs ou comptes restent connectés en même temps, et on passe de l'un à l'autre sans mot de passe.
- La logique de stockage (`SavedServerRepository`) dépend d'interfaces (`KeyValueStore`, `TokenCipher`). Elle est donc testée en JVM pur, migration comprise. La reconnexion (`reconnect`) est testée avec MockWebServer.
- La validation du jeton ajoute deux requêtes au choix d'une tuile, mais pas au démarrage automatique, qui garde le comportement d'avant.
- Non vérifié sur la Shield ni avec le vrai serveur dans cette tranche.
