//! Lists of titles opened from a detail page (ADR 0121): a person's films and
//! series, the titles of a genre or a studio. Same queries and parameters as the
//! Android TV (ADR 0068, 0079, 0087), the verified reference.

use serde::Deserialize;

use super::EmbyError;
use super::models::{BaseItemDto, PersonDto, is_usable_genre};

/// Titles read for one list: one request, enough for a home library.
pub const TITLES_LIMIT: u32 = 100;

/// Similar titles shown under a film or series, as on the TV (ADR 0084).
pub const SIMILAR_LIMIT: u32 = 12;

/// Collections of a title shown on its page, as on the TV.
pub const MAX_COLLECTIONS: usize = 3;

/// Titles read for a genre or a studio, sorted by name: a genre may hold many.
pub const NAMED_TITLES_LIMIT: u32 = 200;

/// What a list of titles shows, as the UI asks for it.
#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum TitlesSource {
    /// Films and series of a person, newest first.
    Person { id: String },
    /// Films or series of one genre, by name.
    Genre {
        name: String,
        #[serde(rename = "itemType")]
        item_type: String,
    },
    /// Films or series of one studio or network, by name.
    Studio {
        name: String,
        #[serde(rename = "itemType")]
        item_type: String,
    },
    /// Titles of a collection (BoxSet), by premiere date.
    Collection { id: String },
    /// Films and series like a title (`/Items/{Id}/Similar`), the title left out.
    Similar { id: String },
}

/// A collection a title belongs to.
#[derive(Debug, Clone, serde::Serialize, PartialEq, Eq)]
pub struct CollectionRef {
    pub id: String,
    pub name: String,
}

/// The item's `Collections` (`[{Name, Id}]`, the id a number or a string):
/// named ones with a usable id, once each, at most `MAX_COLLECTIONS`.
pub fn collection_refs(raw: Option<&serde_json::Value>) -> Vec<CollectionRef> {
    let Some(serde_json::Value::Array(list)) = raw else {
        return Vec::new();
    };
    let mut out: Vec<CollectionRef> = Vec::new();
    for entry in list {
        let id = match entry.get("Id") {
            Some(serde_json::Value::String(s)) => s.trim().to_owned(),
            Some(serde_json::Value::Number(n)) => n.to_string(),
            _ => continue,
        };
        let name = entry
            .get("Name")
            .and_then(|n| n.as_str())
            .map(str::trim)
            .unwrap_or("");
        if !is_item_id(&id) || name.is_empty() || out.iter().any(|c| c.id == id) {
            continue;
        }
        out.push(CollectionRef {
            id,
            name: name.to_owned(),
        });
        if out.len() == MAX_COLLECTIONS {
            break;
        }
    }
    out
}

/// Query of `GET /Items/{Id}/Similar` (TV `EmbyClient.similar`); `None` for a bad id.
pub fn similar_params(id: &str, user_id: &str) -> Option<Vec<(&'static str, String)>> {
    is_item_id(id).then(|| {
        vec![
            ("UserId", user_id.to_owned()),
            ("Limit", SIMILAR_LIMIT.to_string()),
            ("IncludeItemTypes", "Movie,Series".into()),
            ("Fields", "PrimaryImageAspectRatio,ProductionYear".into()),
            ("EnableUserData", "true".into()),
            ("ImageTypeLimit", "1".into()),
            ("EnableImageTypes", "Primary".into()),
        ]
    })
}

/// Same rule as the path segments: ASCII letters, digits and `-` only.
pub fn is_item_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 128 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
}

/// Query of `GET /Users/{UserId}/Items` for `source`; a bad id or name is refused.
pub fn titles_params(source: &TitlesSource) -> Result<Vec<(&'static str, String)>, EmbyError> {
    let invalid = || EmbyError::InvalidResponse("identifiant invalide".into());
    let common = [
        ("Fields", "PrimaryImageAspectRatio,ProductionYear"),
        ("EnableUserData", "true"),
        ("ImageTypeLimit", "1"),
        ("EnableImageTypes", "Primary"),
    ];
    let mut params: Vec<(&'static str, String)> = match source {
        TitlesSource::Person { id } => {
            if !is_item_id(id) {
                return Err(invalid());
            }
            vec![
                ("PersonIds", id.clone()),
                ("Recursive", "true".into()),
                ("IncludeItemTypes", "Movie,Series".into()),
                ("SortBy", "ProductionYear,SortName".into()),
                ("SortOrder", "Descending".into()),
                ("Limit", TITLES_LIMIT.to_string()),
            ]
        }
        TitlesSource::Genre { name, item_type } | TitlesSource::Studio { name, item_type } => {
            let filter = if matches!(source, TitlesSource::Genre { .. }) {
                "Genres"
            } else {
                "Studios"
            };
            // `|` separates names in these filters and has no escape (ADR 0079, 0087).
            if !is_usable_genre(name) || !matches!(item_type.as_str(), "Movie" | "Series") {
                return Err(EmbyError::InvalidResponse("filtre invalide".into()));
            }
            vec![
                (filter, name.trim().to_owned()),
                ("Recursive", "true".into()),
                ("IncludeItemTypes", item_type.clone()),
                ("SortBy", "SortName".into()),
                ("SortOrder", "Ascending".into()),
                ("Limit", NAMED_TITLES_LIMIT.to_string()),
            ]
        }
        TitlesSource::Collection { id } => {
            if !is_item_id(id) {
                return Err(invalid());
            }
            vec![
                ("ParentId", id.clone()),
                ("SortBy", "PremiereDate,ProductionYear,SortName".into()),
                ("SortOrder", "Ascending".into()),
                ("Limit", TITLES_LIMIT.to_string()),
            ]
        }
        // Read from `/Items/{Id}/Similar`, with `similar_params`.
        TitlesSource::Similar { .. } => return Err(invalid()),
    };
    params.extend(common.iter().map(|(k, v)| (*k, (*v).to_owned())));
    Ok(params)
}

/// Films and series only, once each, in the order of `source`: a person's newest
/// year first, titles without a year last, A → Z within a year (one `SortOrder`
/// applies to every `SortBy` key, so the server puts names Z → A).
pub fn order_titles(source: &TitlesSource, items: Vec<BaseItemDto>) -> Vec<BaseItemDto> {
    let mut seen = std::collections::HashSet::new();
    let mut items: Vec<BaseItemDto> = items
        .into_iter()
        .filter(|i| matches!(i.item_type.as_str(), "Movie" | "Series"))
        .filter(|i| seen.insert(i.id.clone()))
        .collect();
    match source {
        TitlesSource::Person { .. } => items.sort_by(|a, b| {
            a.production_year
                .is_none()
                .cmp(&b.production_year.is_none())
                .then(b.production_year.cmp(&a.production_year))
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
        }),
        // Release order: dated titles first, by day, else by year; then A → Z.
        TitlesSource::Collection { .. } => items.sort_by(|a, b| {
            let key = |i: &BaseItemDto| {
                i.premiere_date
                    .as_deref()
                    .and_then(|d| d.get(..10))
                    .map(str::to_owned)
                    .or_else(|| i.production_year.map(|y| format!("{y:04}")))
            };
            let (ka, kb) = (key(a), key(b));
            ka.is_none()
                .cmp(&kb.is_none())
                .then(ka.cmp(&kb))
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
        }),
        // Sorted by the server on the sort name, which the client does not read.
        TitlesSource::Genre { .. } | TitlesSource::Studio { .. } => {}
        // The server's order of likeness, the title itself left out.
        TitlesSource::Similar { id } => {
            items.retain(|i| i.id != *id);
            items.truncate(SIMILAR_LIMIT as usize);
        }
    }
    items
}

/// The person item's id, a string or a number, when it can go into a path.
pub fn person_id(person: &PersonDto) -> Option<String> {
    let id = match person.id.as_ref()? {
        serde_json::Value::String(s) => s.trim().to_owned(),
        serde_json::Value::Number(n) => n.to_string(),
        _ => return None,
    };
    is_item_id(&id).then_some(id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn collections_parse_like_the_tv() {
        let raw = serde_json::json!([
            {"Name": "Dune - Saga", "Id": 120034},
            {"Name": "Dune - Saga", "Id": "120034"},
            {"Name": "  ", "Id": "5"},
            {"Name": "Sans id"},
            {"Name": "Mauvais", "Id": "../x"},
            {"Name": "B", "Id": "2"},
            {"Name": "C", "Id": "3"},
            {"Name": "D", "Id": "4"}
        ]);
        let refs = collection_refs(Some(&raw));
        let ids: Vec<_> = refs.iter().map(|c| c.id.as_str()).collect();
        assert_eq!(ids, ["120034", "2", "3"]);
        assert_eq!(refs[0].name, "Dune - Saga");
        assert!(collection_refs(None).is_empty());
        assert!(collection_refs(Some(&serde_json::json!({"Id": 1}))).is_empty());
    }

    #[test]
    fn collection_titles_in_release_order() {
        let mut a = item("a", "Movie", "Dune 2", Some(2024));
        a.premiere_date = Some("2024-02-27T23:00:00.0000000Z".into());
        let mut b = item("b", "Movie", "Dune", Some(2021));
        b.premiere_date = Some("2021-09-14T22:00:00.0000000Z".into());
        let c = item("c", "Movie", "Dune 1984", Some(1984));
        let d = item("d", "Movie", "Inédit", None);
        let source = TitlesSource::Collection { id: "9".into() };
        let ids: Vec<_> = order_titles(&source, vec![d, a, b, c])
            .into_iter()
            .map(|i| i.id)
            .collect();
        assert_eq!(ids, ["c", "b", "a", "d"]);
        let p = titles_params(&source).unwrap();
        assert!(p.contains(&("ParentId", "9".into())));
        assert!(p.contains(&("SortBy", "PremiereDate,ProductionYear,SortName".into())));
    }

    #[test]
    fn similar_leaves_the_title_out() {
        let source = TitlesSource::Similar { id: "a".into() };
        let mut list = vec![
            item("a", "Movie", "Soi", None),
            item("b", "Series", "B", None),
        ];
        list.extend((0..20).map(|n| item(&format!("x{n}"), "Movie", "X", None)));
        list.push(item("b", "Series", "B", None));
        let out = order_titles(&source, list);
        assert_eq!(out.len(), SIMILAR_LIMIT as usize);
        assert_eq!(out[0].id, "b");
        assert!(titles_params(&source).is_err());
        assert!(similar_params("../x", "u").is_none());
        let p = similar_params("a", "u").unwrap();
        assert!(p.contains(&("IncludeItemTypes", "Movie,Series".into())));
    }

    fn item(id: &str, kind: &str, name: &str, year: Option<i32>) -> BaseItemDto {
        serde_json::from_value(serde_json::json!({
            "Id": id, "Type": kind, "Name": name, "ProductionYear": year,
        }))
        .unwrap()
    }

    fn person(json: serde_json::Value) -> PersonDto {
        serde_json::from_value(json).unwrap()
    }

    #[test]
    fn person_query_matches_the_tv() {
        let p = titles_params(&TitlesSource::Person { id: "30209".into() }).unwrap();
        let get = |k: &str| p.iter().find(|(n, _)| *n == k).map(|(_, v)| v.as_str());
        assert_eq!(get("PersonIds"), Some("30209"));
        assert_eq!(get("Recursive"), Some("true"));
        assert_eq!(get("IncludeItemTypes"), Some("Movie,Series"));
        assert_eq!(get("SortBy"), Some("ProductionYear,SortName"));
        assert_eq!(get("SortOrder"), Some("Descending"));
        assert_eq!(get("Limit"), Some("100"));
        assert_eq!(get("EnableImageTypes"), Some("Primary"));
    }

    #[test]
    fn a_bad_person_id_is_refused() {
        for id in ["", "../Users", "12 34", "a&b=c"] {
            assert!(
                titles_params(&TitlesSource::Person { id: id.into() }).is_err(),
                "{id}"
            );
        }
    }

    #[test]
    fn genre_and_studio_queries_filter_by_name_and_type() {
        let genre = TitlesSource::Genre {
            name: " Science-Fiction ".into(),
            item_type: "Movie".into(),
        };
        let p = titles_params(&genre).unwrap();
        let get = |k: &str| p.iter().find(|(n, _)| *n == k).map(|(_, v)| v.clone());
        assert_eq!(get("Genres").as_deref(), Some("Science-Fiction"));
        assert_eq!(get("IncludeItemTypes").as_deref(), Some("Movie"));
        assert_eq!(get("Recursive").as_deref(), Some("true"));
        assert_eq!(get("SortBy").as_deref(), Some("SortName"));
        assert_eq!(get("Limit").as_deref(), Some("200"));
        assert_eq!(get("Studios"), None);

        let studio = TitlesSource::Studio {
            name: "HBO".into(),
            item_type: "Series".into(),
        };
        let p = titles_params(&studio).unwrap();
        assert!(p.contains(&("Studios", "HBO".into())));
        assert!(p.contains(&("IncludeItemTypes", "Series".into())));
    }

    #[test]
    fn unusable_names_and_types_are_refused() {
        for (name, kind) in [
            ("Drame|Comédie", "Movie"),
            ("", "Movie"),
            ("  ", "Series"),
            ("Drame", "Episode"),
            ("Drame", "Movie,Series"),
        ] {
            let source = TitlesSource::Genre {
                name: name.into(),
                item_type: kind.into(),
            };
            assert!(titles_params(&source).is_err(), "{name} {kind}");
        }
        let long = TitlesSource::Studio {
            name: "x".repeat(101),
            item_type: "Movie".into(),
        };
        assert!(titles_params(&long).is_err());
    }

    #[test]
    fn sources_decode_from_the_ui_shape() {
        let s: TitlesSource =
            serde_json::from_str(r#"{"kind":"studio","name":"HBO","itemType":"Series"}"#).unwrap();
        assert_eq!(
            s,
            TitlesSource::Studio {
                name: "HBO".into(),
                item_type: "Series".into()
            }
        );
        let p: TitlesSource = serde_json::from_str(r#"{"kind":"person","id":"1"}"#).unwrap();
        assert_eq!(p, TitlesSource::Person { id: "1".into() });
    }

    #[test]
    fn person_titles_newest_first_then_a_to_z() {
        let source = TitlesSource::Person { id: "1".into() };
        let out = order_titles(
            &source,
            vec![
                item("1", "Movie", "Zorro", Some(2020)),
                item("2", "Movie", "alpha", Some(2020)),
                item("3", "Series", "Sans année", None),
                item("4", "Episode", "Pilote", Some(2024)),
                item("5", "Movie", "Récent", Some(2023)),
                item("1", "Movie", "Zorro", Some(2020)),
            ],
        );
        let ids: Vec<_> = out.iter().map(|i| i.id.as_str()).collect();
        assert_eq!(ids, ["5", "2", "1", "3"]);
    }

    #[test]
    fn person_id_reads_a_string_or_a_number() {
        let id = |j| person_id(&person(j));
        assert_eq!(
            id(serde_json::json!({"Id": " 30209 "})).as_deref(),
            Some("30209")
        );
        assert_eq!(
            id(serde_json::json!({"Id": 30209})).as_deref(),
            Some("30209")
        );
        assert_eq!(id(serde_json::json!({"Id": null})), None);
        assert_eq!(id(serde_json::json!({"Id": "a/b"})), None);
        assert_eq!(id(serde_json::json!({"Id": true})), None);
        assert_eq!(id(serde_json::json!({})), None);
    }
}
