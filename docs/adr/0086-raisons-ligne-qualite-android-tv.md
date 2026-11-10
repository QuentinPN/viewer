# ADR 0086 — Raisons du transcodage sur la ligne « Qualité » (Android TV)

Statut : Accepté (2026-11-10). Corrige la ligne « Qualité » de l'ADR 0081 ; reprend les raisons de l'ADR 0061.

## Contexte
Sous la ligne « Qualité » du menu ▼, la méthode de lecture s'affichait toujours « Transcodage · raison non précisée ». « Informations de lecture » disait pourtant « débit trop élevé ». Sur la Shield, un diagnostic temporaire, qui ne journalisait que les noms des paramètres, a montré que la `TranscodingUrl` reçue après un changement de qualité portait bien `TranscodeReasons=ContainerBitrateExceedsLimit`. La ligne appelait `methodLabel` avec une liste vide.

## Décision
- `Quality` garde `reasons`, les raisons lues dans l'URL, comme pour `PlaybackFacts`. `Quality.of` et `applied` les reçoivent au premier chargement et après un changement de qualité. La ligne utilise le même libellé qu'« Informations de lecture ».

## Alternatives
- **Retirer les raisons de la ligne** : on perdrait l'explication à l'endroit où l'on change le débit. Rejeté.

## Conséquences
- Les deux endroits affichent la même chose. Si le serveur ne donne pas de raison, les deux affichent « raison non précisée ».

## Vérification
- `QualityTest` : la ligne affiche les raisons après `applied` et dès `Quality.of`. `testDebugUnitTest` (422 tests, 0 échec), `assembleDebug` et `lintDebug` passent.
- Shield, Overgeared S1E7, passage à 1,5 Mbit/s : la ligne affiche « Transcodage · débit trop élevé · ◀ ▶ puis OK pour changer ».
