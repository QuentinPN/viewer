# ADR 0123 — Lecture liée ordi ↔ TV : relais par Emby

- Statut : accepté
- Date : 2026-11-14

## Contexte

Le propriétaire partage son écran sur Discord et veut regarder le même épisode sur la Shield en même temps. Une action sur la TV (pause, ±10 s, passer l'intro…) doit aussi s'appliquer sur l'ordi (hand-off `docs/handoff/synchro-tv-discord.md`). Discord ajoute environ 0,3 à 1 s de retard pour les amis, donc l'objectif est un écart de moins de 150 ms entre la TV et l'ordi.

Deux transports étaient possibles : le relais de sessions d'Emby (sockets déjà ouvertes par ADR 0110) ou un lien direct sur le réseau local. La sonde du 2026-11-14 (`docs/evidence/synchro-tv-relais-emby-2026-11-14.md`, Emby 4.10.2.0) a mesuré :

- `Sessions/{Id}/Playing/*` et `Sessions/{Id}/Command` sont relayés dans les deux sens entre deux sessions du même utilisateur ;
- `SendString` transporte une chaîne JSON intacte (testé jusqu'à 931 caractères) ;
- latence relais médiane 4–5 ms, p95 15–44 ms, aucune perte sur 135 envois (deux sessions sur la même machine) ;
- sans `SupportsMediaControl: true`, une session n'est pas listée par `/Sessions?ControllableByUserId=` ;
- le message reçu ne nomme que l'utilisateur, jamais la session qui l'envoie ;
- une session dont la socket est fermée disparaît de la liste en moins de 1,5 s, mais un envoi vers elle répond encore 204.

## Décision

- **Transport : le relais Emby.** Pas de port ouvert, pas d'appairage, pas d'invite du pare-feu Windows. Le lien direct (la TV héberge, l'ordi s'y connecte) n'est pas construit. On n'y revient que si le test réel de l'étape 6 dépasse 150 ms au p95 entre le PC et la Shield.
- **Un seul canal : `POST /Sessions/{Id}/Command` avec `{"Name":"SendString","Arguments":{"String":"<json>"}}`.** Il sert aux commandes, à l'état et aux battements. Le chemin `/Command/SendString` est écarté : il perd les arguments. Les `Playstate` natifs ne sont pas utilisés pour la liaison, pour deux raisons : ils ne disent pas qui les envoie, et ils ne savent pas porter « prêt », « passer l'intro », « chapitre » ni l'état horodaté.
- **Enveloppe versionnée**, au plus 1 Ko : `{"tv":"sync","v":1,"from":"<Id de session>","link":"<id de liaison>","seq":<n>,"kind":"…",…}`. Une chaîne qui ne se lit pas, qui dépasse la taille, qui a une autre version, un autre `link` ou un `from` autre que la session liée est ignorée sans erreur. Seul le `kind` est journalisé, jamais le contenu.
- **Déclaration** à l'ouverture de la socket, sur les deux apps : `POST /Sessions/Capabilities/Full` avec `SupportsMediaControl: true`, `PlayableMediaTypes: ["Video"]`, `SupportedCommands: ["SendString"]`. Sans cela, l'autre appareil ne trouve pas la session. Conséquence acceptée : le tableau de bord Emby montre nos sessions comme pilotables. Les `Playstate` et `GeneralCommand` venant d'un autre client sont ignorés.
- **Découverte** : `GET /Sessions?ControllableByUserId={UserId}`, sessions `Client == "Viewer"` autres que la sienne. Le serveur limite déjà la liste au même utilisateur. Aucun code à saisir.
- **Perte du lien** : un envoi qui répond 204 ne prouve rien. Chaque côté envoie un battement toutes les secondes (l'état du leader en tient lieu côté ordi). Sans message du pair pendant 5 s, le lien est perdu : message à l'écran, chaque lecteur continue seul.
- **Horloges** : les positions sont absolues. Le suiveur estime le trajet comme la moitié d'un aller-retour `ping`/`pong` et corrige la position reçue de ce délai. Aucune horloge murale n'est comparée entre appareils.
- **Secrets** : rien ne change par rapport à l'ADR 0110. Le jeton reste dans l'en-tête HTTP et dans l'URL de la socket. Il n'est jamais dans une charge utile ni dans un journal.

## Conséquences

- Étape 1 : la socket Emby reste ouverte toute la session (pas seulement sur l'accueil) sur les deux apps, avec la déclaration ci-dessus. L'ADR 0110 sera amendée.
- Le protocole (étape 2) est défini sur cette enveloppe, en Rust et en Kotlin, avec les mêmes cas de test.
- Limite : le relais dépend du serveur. Si Emby redémarre, le lien tombe et doit être refait.
- Non vérifié : latence et pertes entre deux machines, longue session, redémarrage du serveur.
