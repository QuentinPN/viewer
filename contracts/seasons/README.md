# Calendrier des logos saisonniers

`vectors.json` : tableau de `{ "date": "AAAA-MM-JJ", "season": "<id>" }`, lu par les tests de `src/lib/brand/season.ts` (desktop) et de `io.github.quentinpn.viewer.tv.brand` (Android TV). Les deux implémentations doivent donner la même saison pour chaque date.

Entrée : la date calendaire locale (année, mois, jour), jamais un horodatage UTC.

Périodes (bornes incluses) :

- `new-year` : 27 déc. au 1er janv.
- `epiphany` : 1er au 15 janv.
- `winter` : 16 janv. au dernier jour de février (29 février compris)
- `valentine` : 10 au 14 févr.
- `april-fools` : 1er avr.
- `easter` : lundi de la Semaine sainte (dimanche de Pâques - 6 jours) au lundi de Pâques (+ 1 jour), Pâques grégorien (algorithme anonyme grégorien / Meeus)
- `music` : 21 juin
- `summer` : 1er juil. au 31 août
- `bastille` : 14 juil.
- `autumn` : 1er sept. au 31 oct.
- `halloween` : 24 au 31 oct.
- `christmas` : 1er au 26 déc.
- `default` : toute autre date

Chevauchements : la période la plus spécifique gagne, dans cet ordre (priorité décroissante) : `april-fools`, `music`, `bastille`, `valentine`, `new-year`, `halloween`, `easter`, `epiphany`, `christmas`, `winter`, `summer`, `autumn`, puis `default`.

Les dates de Pâques viennent de `dateutil.easter` (`EASTER_WESTERN`, python-dateutil 2.9), recoupées avec l'algorithme de Meeus sur 1900-2200 et avec 12 dates connues (2000, 2011, 2018, 2019, 2024 à 2030, 2038).
