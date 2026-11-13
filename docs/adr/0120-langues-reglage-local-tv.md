# ADR 0120 — Langues audio et sous-titres réglables sur Android TV

Statut : Accepté (2026-11-13). Applique à la TV la décision du propriétaire décrite dans l'ADR 0115 (« Langues : compte Emby et réglage local », rédigée côté desktop) ; complète les ADR 0042, 0055 et 0065.

## Contexte
La TV suivait uniquement les préférences du compte Emby (`player/AccountTracks.kt`, ADR 0065). Décision du propriétaire : les deux apps suivent le compte par défaut, avec un réglage local qui le remplace sur l'appareil.

## Décision
- Réglages › « Langues » : « Langue audio », « Mode des sous-titres », « Langue des sous-titres ». Chaque choix commence par « Comme le compte Emby (…) », qui affiche ce que dit le compte (lu par `AppServices.accountTracks`), puis la liste ; OK passe à la valeur suivante, comme les autres réglages TV.
- Langues proposées (codes Emby ISO 639-2/B) : fre, eng, jpn, ger, spa, ita, kor, por, dut, chi. Modes : Par défaut, Intelligent, Forcés uniquement, Toujours, Pour malentendants, Aucun.
- Stockage : `PlaybackSettingsStore`, par utilisateur Emby, sur l'appareil (`audio.language`, `subtitle.mode`, `subtitle.language`) ; absent = suit le compte. Rien n'est écrit sur le serveur.
- Sélection (`player/LanguageOverrides.kt`, logique pure testée) : pour un type réglé localement, la langue locale passe avant celles du compte, le mode local remplace celui du compte (si seule la langue est réglée, le mode du compte s'applique, à défaut « Par défaut ») ; une langue audio locale l'emporte sur « lire la piste par défaut ». Ordre final dans `decide` : choix mémorisé du lecteur (ADR 0042) → réglage local → choix du serveur (`DefaultAudio/SubtitleStreamIndex`, qui ne reflète que le compte) → préférences du compte → mpv.
- Changer un réglage de langue efface le choix mémorisé du lecteur pour ce type, sinon il masquerait le nouveau réglage ; une piste choisie ensuite pendant la lecture redevient prioritaire.

## Limites
- En transcodage, l'audio et les sous-titres incrustés sont ceux du serveur (choisis d'après le compte) : le réglage local ne s'applique pas. L'envoyer dans PlaybackInfo (`AudioStreamIndex`) serait une évolution séparée.
- Non vérifié sur la Shield ni sur le vrai serveur ; tests JVM, compilation et lint passés.
