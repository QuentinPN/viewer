//! Lists of titles opened from a detail page (ADR 0121): a person's films and
//! series. Same queries as the Android TV (ADR 0068), the verified reference.

use serde::Deserialize;

use super::EmbyError;
use super::models::{BaseItemDto, PersonDto};

/// Titles read for one list: one request, enough for a home library.
pub const TITLES_LIMIT: u32 = 100;

/// What a list of titles shows, as the UI asks for it.
#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum TitlesSource {
    /// Films and series of a person, newest first.
    Person { id: String },
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
