# Enveloppe de lecture liée : serde_json contre encoding/json (unité sync-core)

`src-tauri/src/sync/envelope.rs` lit et écrit l'enveloppe par `serde_json::Value`. Le crate est en version 1.0.154 (Cargo.lock de src-tauri). Ses dépendances verrouillées sont `itoa`, `memchr`, `serde`, `serde_core` et `zmij`, sans `indexmap`. `preserve_order` est donc coupé. `arbitrary_precision` et `float_roundtrip` sont coupés aussi. Le paquet Go `internal/devsync` reproduit son comportement. Sources lues dans `~/.cargo/registry/src/*/serde_json-1.0.154/`.

## Écriture
- `Map` est un `BTreeMap<String, Value>` : `Value::to_string` écrit les clés triées par octets, sans espace. Kotlin écrit dans l'ordre d'insertion. Le récepteur ne dépend pas de l'ordre.
- Les chaînes (`ser.rs`, `format_escaped_str`) :
  - `"` et `\` sont échappés ;
  - `\b`, `\t`, `\n`, `\f` et `\r` ont leur forme courte ;
  - les autres contrôles C0 s'écrivent `\u00xx`, en hexadécimal minuscule ;
  - tout le reste est écrit tel quel, y compris `<`, `>`, `&`, DEL et U+2028.
  encoding/json échappe en plus `<>&` (sauf `SetEscapeHTML(false)`), ainsi que U+2028 et U+2029 dans tous les cas. Il ne convient donc pas : `wire.go` écrit le texte à la main.
- Les entiers sont écrits en décimal (`itoa`).

## Lecture
- **Classes de nombres** (`de.rs`, `parse_integer` / `parse_number`) :
  - un littéral sans `.` ni exposant et sans signe est un `u64` s'il tient, sinon un `f64` ;
  - avec `-`, il est un `i64` négatif. `-0` et toute valeur sous `i64::MIN` deviennent un `f64`, car `wrapping_neg` donne ≥ 0 ;
  - `.`, `e` et `E` donnent toujours un `f64`.
- **`as_u64` et `as_i64`** (`number.rs`) :
  - `as_u64` accepte seulement un PosInt ;
  - `as_i64` accepte un PosInt jusqu'à `i64::MAX`, ou un NegInt ;
  - un `f64` donne toujours `None`. Ainsi `"v":1.0`, `"seq":-0` et `"positionMs":5.0` sont refusés.
- **Plage des flottants** (`de.rs`, `f64_from_parts`, `parse_exponent_overflow`, `parse_long_integer`) :
  - un flottant qui déborde vers l'infini est refusé avec l'erreur `NumberOutOfRange`, même dans un champ inconnu ;
  - un dépassement vers zéro donne 0.
  Sans `float_roundtrip`, le calcul est `significande × 10^exp` en f64. Go arrondit exactement, donc le seuil du débordement peut différer tout près de `f64::MAX` (écart assumé).
- **Profondeur** (`de.rs`, `remaining_depth: 128`, `check_recursion!`) : le 128e niveau d'imbrication est refusé, et 127 niveaux passent. Avec encoding/json, la limite est 10000.
- **Surrogates** (`read.rs`, `parse_unicode_escape` avec `validate = true`, utilisé pour les chaînes et les clés) :
  - un `\uDC00`–`\uDFFF` isolé est refusé ;
  - un `\uD800`–`\uDBFF` qui n'est pas suivi d'un `\u` de U+DC00 à U+DFFF est refusé aussi.
  encoding/json les remplace par U+FFFD.
- **Clé en double** : la dernière l'emporte (`BTreeMap::insert`), comme dans une map encoding/json.
- `from_str` prend un `&str`, donc toujours de l'UTF-8 valide. Go refuse une entrée invalide avec le code `json`, alors qu'encoding/json la remplacerait.

## Vérification (2026-11-22)
Un projet cargo jetable a été construit hors ligne (`--offline`), par `scripts/with-msvc.ps1`. Il compilait une copie d'envelope.rs avec `serde_json = "=1.0.154"` et le Cargo.lock de src-tauri, puis il a été supprimé.
- Il a décodé les 53 entrées de `decodeEdgeCases` (envelope_test.go), passées depuis Go en JSON. 52 codes de refus sur 53 sont identiques à ceux qu'attend Go. Le 53e est « invalid UTF-8 » : l'octet est arrivé en U+FFFD par le transport JSON, et un `&str` Rust ne peut pas le porter.
- Il a encodé les 10 enveloppes de `Test_go_encode_writes_sorted_keys_like_serde_json` et celle de `Test_go_encode_escapes_strings_like_serde_json`. Les 11 textes sont identiques à ceux qu'attend Go, octet par octet.
