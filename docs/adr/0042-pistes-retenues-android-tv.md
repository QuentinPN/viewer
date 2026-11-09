# ADR 0042 — Pistes retenues et sous-titres au-dessus des contrôles (Android TV)

Statut : Accepté (2026-11-09). Complète l'ADR 0041.

## Contexte
L'ADR 0041 posait deux limites :
- une piste choisie dans le menu ne servait que pour la lecture en cours ;
- les sous-titres de mpv passaient sous la couche des contrôles.

Avec un anime regardé en japonais sous-titré, il fallait refaire le choix à chaque épisode.

## Décision
- **Choix retenu par utilisateur, sur l'appareil.** Chaque choix fait dans le menu enregistre sa langue et son titre de piste, ou « sous-titres désactivés ». L'audio et les sous-titres sont enregistrés à part, dans des préférences Android simples (`tracks`), avec l'id Emby de l'utilisateur dans la clé. Une langue n'est pas un secret, donc pas de Keystore.
- **Appliqué au chargement de chaque fichier**, après l'ajout des sous-titres externes. On cherche une piste de même langue et de même titre (« Forced », « CC »). À défaut, la première piste de cette langue est choisie. Si aucune ne convient, le choix de mpv reste. Les codes `fre`, `fra` et `fr` désignent la même langue (`choose`, `sameLanguage` dans `player/TrackPreferences.kt`).
- **Sous-titres remontés pendant que les contrôles sont visibles.** La couche mesure la part de l'écran couverte par son texte, sans le fondu. Le lecteur règle alors `sub-pos` de mpv (100 = place normale), puis le remet à 100 quand les contrôles disparaissent.

## Alternatives
- **Préférences de langue du compte Emby** (`AudioLanguagePreference`, `SubtitleMode`, `DefaultAudioStreamIndex` de PlaybackInfo) : c'est le même réglage que sur les autres clients. Mais il faut faire correspondre les index Emby aux ids mpv, et modifier ces préférences depuis l'app changerait la configuration du compte sur le serveur. Reporté ; à reprendre si l'on veut le même choix partout.
- **Ids de piste mpv** : ils changent d'un fichier à l'autre. Écarté.
- **`sub-margin-y`** : sans effet sur les sous-titres ASS sauf `sub-ass-override=force`, qui écrase leur style. Écarté au profit de `sub-pos`.

## Conséquences
- Le choix est propre à cette Shield : le client Windows et les autres appareils ne le voient pas.
- Un sous-titre placé en haut de l'image (panneau, titre) est aussi déplacé par `sub-pos` pendant que les contrôles sont visibles.
- Un choix qui ne trouve aucune langue correspondante (un film anglais sans piste japonaise) laisse la piste par défaut du fichier, sans message.
