# Android TV — liste de vérification pour le propriétaire

Date : 2026-11-10. Appareil : NVIDIA Shield (Android TV), avec la télécommande.

Cette liste reprend tout ce qui reste « non vérifié » dans `ROADMAP.md` (entrées Android TV) et dans `docs/evidence/tv-client-*.md` / `docs/evidence/gate-tv1-2026-11-09.md`, et que seul le propriétaire peut contrôler : à l'œil, à l'oreille, à la télécommande, avec les données de son propre compte ou après une décision côté serveur. Les points déjà vérifiés par une tranche suivante, ceux propres au bureau Windows et l'aperçu d'image pendant l'avance rapide (refusé) n'y figurent pas.

**Signaler un échec** : noter le titre lu (et l'épisode), l'heure à laquelle c'est arrivé et, si possible, une photo de l'écran. Une ligne par problème suffit ; l'heure permet de retrouver le journal correspondant.

Cocher `[x]` ce qui se passe comme prévu.

## Lecture et image

- [ ] Lire un film 4K HDR10 : le téléviseur passe en HDR (et non en SDR terne), couleurs et luminosité correctes à l'œil (ADR 0038).
- [ ] Lire un fichier Dolby Vision, s'il y en a un : l'image s'affiche avec des couleurs normales (ni violettes ni vertes), ou un message clair en cas d'échec (ADR 0038).
- [ ] Regarder un film complet, du début à la fin : pas de saccade, pas d'arrêt, la Shield ne chauffe pas anormalement (ADR 0038).
- [ ] Sur un fichier HDR, ouvrir ▼ › « Informations de lecture » : la ligne couleur indique le HDR (ADR 0061).
- [ ] Sur un film qui n'est pas en 16:9 (bandes noires), ▼ › « Lecture » › « Format » : « Remplir l'écran » rogne, « Étirer » déforme, « Original » revient ; les sous-titres restent lisibles dans chaque format (ADR 0078).
- [ ] Sur un film qui a deux versions (deux fichiers dans Emby) : un bouton « Version : … » apparaît sur sa fiche, le changer puis « Lire » lit bien l'autre version, et la reprise est ensuite proposée (ADR 0058).
- [ ] Sur un épisode qui a plusieurs versions : la fiche de l'épisode propose la version comme la fiche d'un film (ADR 0060).
- [ ] ▼ › « Lecture » › « Qualité » : passer à 1,5 Mbit/s puis OK, puis à une autre valeur basse puis OK : la lecture repart chaque fois au même endroit, sans erreur (ADR 0081).
- [ ] Avec un plafond bas dans Réglages (transcodage) et l'audio japonais retenu pour une série, lancer un épisode : il démarre directement en japonais (ADR 0084).
- [ ] Sur un film qui a une bande-annonce locale (fichier dans Emby, pas YouTube) : « Bande-annonce » la lit dans le lecteur, et Retour revient à la fiche sans reprise enregistrée (ADR 0083).

## Son

- [ ] Écouter un film en AC3 5.1 puis un épisode en AAC stéréo : le son est propre, synchrone, sans craquement (ADR 0038).
- [ ] Lire un fichier en AAC stéréo 44,1 kHz : le son est bien présent (repli OpenSL ES), sans coupure (ADR 0038).
- [ ] Lire un fichier DTS ou DTS-HD : le son sort, et l'ampli affiche le format attendu si la sortie directe (bitstream) est active (ADR 0038).
- [ ] Avec l'ampli ou la barre de son : vérifier si le son 5.1 arrive en multicanal ; ▼ › « Informations de lecture » indique la sortie audio utilisée (ADR 0061).
- [ ] ▼ › « Synchronisation » › « Décalage audio » : ◀ ▶ décalent le son de façon audible, OK revient à « Aucun » ; à l'épisode suivant, le décalage est revenu à « Aucun » (ADR 0062).
- [ ] ▼ › « Lecture » › « Mode nuit » sur une scène bruyante : les explosions sont moins fortes et les dialogues restent audibles ; sans effet attendu si le son part en direct vers l'ampli (ADR 0072).
- [ ] Laisser « Mode nuit » actif et passer à l'élément suivant : le son reste normal, sans coupure ni silence (ADR 0072).

## Sous-titres

- [ ] Lire un fichier avec des sous-titres texte SRT : ils s'affichent avec la taille, la couleur et la position choisies dans Réglages › « Sous-titres » (ADR 0053).
- [ ] Réglages › « Sous-titres » › position « En haut » : les sous-titres s'affichent en haut de l'image (ADR 0053).
- [ ] Lire un fichier avec des sous-titres image (PGS, Blu-ray) : ils s'affichent au bon moment (ADR 0038).
- [ ] Sur un film 4K lourd, changer de sous-titres dans le menu ▼ : une courte attente est possible, puis la nouvelle piste s'affiche (ADR 0038).
- [ ] Sur un fichier sans aucun sous-titre : le menu ▼ ne propose que l'audio et la lecture se passe normalement (ADR 0041).
- [ ] Un panneau ou un texte placé en haut de l'image (ASS) pendant que les contrôles sont affichés : il reste lisible et ne passe pas sous les contrôles (ADR 0042).
- [ ] Choisir un sous-titre externe (« … (externe) ») dans le menu ▼, quitter, relancer un autre épisode de la série : le même sous-titre est repris (ADR 0042).
- [ ] ▼ › « Synchronisation » › « Décalage des sous-titres » : décaler de −0,5 s, la réplique arrive visiblement plus tôt ; à l'épisode suivant, revenu à « Aucun » (ADR 0062).
- [ ] Avec les sous-titres réglés dans Réglages, laisser l'épisode suivant s'enchaîner : l'apparence reste la même (ADR 0053).
- [ ] Première lecture en transcodage d'un épisode jamais lu (sous-titres pas encore extraits par le serveur) : l'image avance et la barre de progression bouge pendant que les sous-titres arrivent ; quitter pendant ce temps ferme le lecteur proprement (ADR 0085).

## Navigation et télécommande

- [ ] Pendant la lecture, maintenir ▶ : les sauts passent de 10 s à 30 s après environ 1 s, puis à 60 s vers 3 s ; un nouvel appui repart à 10 s (ADR 0090).
- [ ] Même chose en maintenant ◀ (retour arrière) : mêmes paliers vers l'arrière (ADR 0090).
- [ ] Si la télécommande a une touche Menu ou des touches média (lecture/pause, avance, retour) : elles agissent dans le lecteur comme OK, ▼ et ◀ ▶ (ADR 0041).
- [ ] Appuyer sur le bouton Accueil de la télécommande pendant la lecture, puis rouvrir l'application : la reprise est proposée au bon endroit (ADR 0040).
- [ ] Dans la grille « Films », maintenir ▼ : la grille défile sans à-coups jusqu'au dernier titre (606), et les pages suivantes se chargent (ADR 0044).
- [ ] Dans la grille, ouvrir « Genre » et appuyer sur ◀ ou ▶ pendant que la liste est ouverte : le focus ne doit pas s'échapper de la liste (ADR 0045).
- [ ] Trier une bibliothèque en « Z → A » puis utiliser « Lettre » ; puis avec un filtre actif : la lettre choisie amène au bon titre (ADR 0070).
- [ ] Sur la carte « Épisode suivant », appuyer sur Retour : même effet qu'« Annuler » (ADR 0043).
- [ ] Avec le menu ▼ ouvert pendant l'intro : le bouton « Passer l'intro » ne gêne pas le menu et ne s'active pas par erreur (ADR 0048).
- [ ] Désactiver les animations dans les paramètres de la Shield : le hero change de titre sans fondu, les écrans recherche et grille restent utilisables (ADR 0059, ADR 0067).
- [ ] Sur un titre sans « Titres similaires » : la rangée n'apparaît pas. Enchaîner plus de 10 titres similaires : Retour redescend la pile sans erreur (ADR 0063).
- [ ] Depuis une page d'épisode ouverte par un titre similaire, ouvrir la série puis revenir : Retour suit le bon chemin (ADR 0063).
- [ ] Depuis la « Distribution » d'une page d'épisode, ouvrir une personne puis Retour, Retour : on revient à l'épisode puis à la série sur la bonne saison (ADR 0068).
- [ ] Dans la recherche, utiliser la saisie vocale : le texte dicté lance la recherche (ADR 0047).
- [ ] Rechercher un mot sans résultat : un message clair s'affiche, pas un écran vide (ADR 0047).
- [ ] Avatar › « Se déconnecter » : retour à l'écran de connexion ; se reconnecter avec son compte fonctionne (ADR 0039).

## Accueil et bibliothèques

- [ ] Avec son propre compte : la rangée « À suivre » montre les prochains épisodes ; OK sur une carte lance l'épisode depuis le début (ADR 0056).
- [ ] Avec des favoris sur son compte : la rangée « Favoris » les montre de A à Z avec leurs affiches ; OK ouvre la fiche et Retour revient sur la même carte (ADR 0091).
- [ ] Avec une liste de lecture vidéo sur son compte : la rangée « Listes de lecture » apparaît ; sa page liste les films et épisodes dans l'ordre de la liste et les ouvre (ADR 0088).
- [ ] Dans une grille, filtres « Vus », « En cours » et « Favoris » : chacun montre les bons titres de son compte (ADR 0045).
- [ ] Dans la bibliothèque Anime : la barre d'outils n'a pas « En cours » et le reste fonctionne (ADR 0045).
- [ ] Affiches : une coche sur un film ou une série vus, une barre de progression sur un film commencé (ADR 0046).
- [ ] Page d'une série : coche sur une saison entièrement vue ; si la saison 1 est finie, la page s'ouvre sur la première saison avec des épisodes restants (ADR 0049).
- [ ] Fiche d'un film déjà vu : le badge « Vu » s'affiche (ADR 0040).
- [ ] Hero de l'accueil : un film parmi les cinq titres, et une série entièrement vue, s'affichent correctement (ADR 0059).
- [ ] Hero : un titre sans logo affiche son nom en texte ; si aucun titre n'a de fond, l'accueil s'affiche sans hero (ADR 0064).
- [ ] Une saison de plus de 100 épisodes : tous les épisodes apparaissent (ADR 0039).
- [ ] Une série avec des épisodes spéciaux (saison 0) : ils s'affichent et se lisent ; un spécial trouvé par la recherche ouvre la bonne saison (ADR 0039, ADR 0054).
- [ ] Fiche d'épisode : les « Invités » d'un épisode qui en a ; le premier et le dernier épisode d'une saison n'ont qu'un seul voisin ; un spécial diffusé dans une autre saison (ADR 0060).
- [ ] Une personne sans photo dans « Distribution » : ses initiales s'affichent (ADR 0068).
- [ ] Un titre sans genres ni personnes : les lignes vides sont absentes, la fiche reste propre (ADR 0050).
- [ ] Un film sans image de fond ni résumé : la fiche s'affiche sans trou (ADR 0040).
- [ ] Un film d'une collection (si son compte en voit) : la rangée « Collection : <nom> » apparaît avec « Ce film » marqué ; si elle n'apparaît jamais, le signaler (ADR 0071).
- [ ] Un élément avec beaucoup de chapitres : la liste « Chapitres » défile ; sur un élément sans chapitres, ▲ montre seulement les contrôles (ADR 0057).
- [ ] « Au hasard » dans une bibliothèque de séries, puis avec un filtre actif : le titre ouvert respecte les filtres (ADR 0077).
- [ ] « Années » dans une bibliothèque de séries ; puis « Réinitialiser » efface la décennie choisie (ADR 0082).
- [ ] Une pastille « Genres » accentuée (« Comédie », « Drame »…) ouvre la bonne grille ; changer le tri dans cette grille ne modifie pas les choix enregistrés de la bibliothèque (ADR 0079).
- [ ] Pastilles « Studio » sur un film, et un studio dont le nom a un accent, `&` ou `+` : la grille du studio s'ouvre avec les bons titres (ADR 0087).

## Réseau et reprise

- [ ] Rapport d'arrêt perdu : couper le réseau pendant la lecture, quitter le lecteur, forcer l'arrêt de l'application, rétablir le réseau, rouvrir l'application : la reprise est ensuite proposée à la bonne position (ADR 0066).
- [ ] Même essai en rouvrant l'application plusieurs heures après : la position est quand même enregistrée par Emby (ADR 0066).
- [ ] Couper le réseau pendant le défilement d'une grille : « Réessayer » apparaît en bas, et marche une fois le réseau rétabli (ADR 0044).
- [ ] Couper le réseau puis lancer une recherche : un état d'erreur s'affiche, pas un plantage (ADR 0047).

## Divers

- [ ] Laisser un épisode aller jusqu'au générique : la carte « Épisode suivant » s'ouvre une fois ; « Annuler » laisse le générique avec « Passer le générique » (ADR 0048).
- [ ] Sur un film ou le dernier épisode d'une série : « Passer le générique » apparaît, sans carte « Épisode suivant » (ADR 0043, ADR 0048).
- [ ] Laisser un épisode passer à l'épisode suivant d'une autre saison : la carte s'affiche et la saison suivante démarre (ADR 0043).
- [ ] Avec « Passer automatiquement » actif dans Réglages : le générique est sauté une fois et la carte « Épisode suivant » s'affiche (ADR 0051).
- [ ] ▼ › « Lecture » › « Minuterie » à 15 min : au bout du temps, « Minuterie écoulée : lecture en pause » et l'épisode suivant ne démarre pas (ADR 0073).
- [ ] « Minuterie » sur « Fin de l'épisode » : la lecture s'arrête au début du générique, sans carte « Épisode suivant » (ADR 0073).
- [ ] Avec un autre utilisateur Emby sur la Shield : il trouve les réglages par défaut, pas ceux du premier compte (ADR 0051, ADR 0053).
- [ ] Préférences de langue audio et sous-titres réglées sur son compte Emby, sur un titre jamais lu sur la TV : les pistes choisies suivent ces préférences, y compris le mode de sous-titres choisi dans Emby (ADR 0055, ADR 0065).
- [ ] Décision serveur : si des marqueurs d'intro ou de générique sont créés dans Emby, « Passer l'intro » doit les utiliser (ADR 0048).
- [ ] Décision serveur : si Emby passe en HTTPS, la connexion et la lecture doivent toujours marcher (ADR 0038).
