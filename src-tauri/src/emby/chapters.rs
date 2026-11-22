//! Chapters of an item for the player: Emby's `Chapters`, kept to the
//! regular `Chapter` markers, in seconds of the file. The intro and credits markers
//! stay with the segments.

use serde::Serialize;

use super::models::ChapterDto;

const TICKS_PER_SECOND: f64 = 10_000_000.0;
/// Chapters closer than this to the previous one are dropped: they would share a
/// mark on the seek bar and one key press would skip nothing.
const MIN_GAP_SECONDS: f64 = 1.0;
/// Names are shown on one line; anything longer is cut.
const MAX_NAME_CHARS: usize = 120;

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Chapter {
    /// Seconds from the start of the file.
    pub start: f64,
    pub name: String,
}

/// The chapters the player offers, in order. Fewer than two give none: a single
/// chapter is the whole file and offers no navigation.
pub fn chapters(dtos: &[ChapterDto], run_time_ticks: Option<i64>) -> Vec<Chapter> {
    let end = run_time_ticks
        .filter(|&t| t > 0)
        .map(|t| t as f64 / TICKS_PER_SECOND);
    let mut found: Vec<(f64, Option<&str>)> = dtos
        .iter()
        .filter(|c| c.marker_type.as_deref().is_none_or(|m| m == "Chapter"))
        .map(|c| {
            (
                c.start_position_ticks as f64 / TICKS_PER_SECOND,
                c.name.as_deref(),
            )
        })
        .filter(|&(start, _)| start >= 0.0 && end.is_none_or(|end| start < end))
        .collect();
    found.sort_by(|a, b| a.0.total_cmp(&b.0));
    let mut kept: Vec<(f64, Option<&str>)> = Vec::with_capacity(found.len());
    for chapter in found {
        if kept
            .last()
            .is_none_or(|last| chapter.0 - last.0 >= MIN_GAP_SECONDS)
        {
            kept.push(chapter);
        }
    }
    if kept.len() < 2 {
        return Vec::new();
    }
    kept.into_iter()
        .enumerate()
        .map(|(i, (start, name))| Chapter {
            start,
            name: clean_name(name).unwrap_or_else(|| format!("Chapitre {}", i + 1)),
        })
        .collect()
}

/// One line of printable text, or `None` when nothing is left.
fn clean_name(name: Option<&str>) -> Option<String> {
    let line: String = name?
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_NAME_CHARS)
        .collect();
    (!line.is_empty()).then_some(line)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dto(ticks: i64, name: Option<&str>, marker: Option<&str>) -> ChapterDto {
        ChapterDto {
            start_position_ticks: ticks,
            name: name.map(str::to_owned),
            marker_type: marker.map(str::to_owned),
        }
    }

    #[test]
    fn maps_the_real_answer_of_a_film() {
        // `/Users/{Id}/Items/43189` on Emby 4.10.2.0 (first four of 27 chapters).
        let found = chapters(
            &[
                dto(0, Some("Opening Credits"), Some("Chapter")),
                dto(2_421_170_000, Some("Horn Island 1977"), Some("Chapter")),
                dto(
                    5_489_650_000,
                    Some("Someone to Take the Fall"),
                    Some("Chapter"),
                ),
                dto(
                    7_002_000_000,
                    Some("Secret of Fly Fishing"),
                    Some("Chapter"),
                ),
            ],
            Some(61_154_010_000),
        );
        assert_eq!(
            found,
            vec![
                Chapter {
                    start: 0.0,
                    name: "Opening Credits".into()
                },
                Chapter {
                    start: 242.117,
                    name: "Horn Island 1977".into()
                },
                Chapter {
                    start: 548.965,
                    name: "Someone to Take the Fall".into()
                },
                Chapter {
                    start: 700.2,
                    name: "Secret of Fly Fishing".into()
                },
            ]
        );
    }

    #[test]
    fn keeps_regular_chapters_only_sorted_and_named() {
        let found = chapters(
            &[
                dto(6_000_000_000, None, None),
                dto(0, Some("  Début\n du film "), Some("Chapter")),
                dto(300_000_000, None, Some("IntroStart")),
                dto(900_000_000, None, Some("IntroEnd")),
                dto(3_000_000_000, Some(""), Some("Chapter")),
                dto(24_000_000_000, None, Some("CreditsStart")),
            ],
            None,
        );
        let names: Vec<_> = found.iter().map(|c| (c.start, c.name.as_str())).collect();
        assert_eq!(
            names,
            [
                (0.0, "Début du film"),
                (300.0, "Chapitre 2"),
                (600.0, "Chapitre 3"),
            ]
        );
    }

    #[test]
    fn drops_what_cannot_be_navigated() {
        // Negative, past the end, and a duplicate within a second.
        let found = chapters(
            &[
                dto(-10, Some("Avant"), None),
                dto(0, Some("A"), None),
                dto(5_000_000, Some("Doublon"), None),
                dto(1_200_000_000, Some("B"), None),
                dto(2_000_000_000, Some("Après la fin"), None),
            ],
            Some(1_800_000_000),
        );
        let names: Vec<_> = found.iter().map(|c| c.name.as_str()).collect();
        assert_eq!(names, ["A", "B"]);

        // One chapter, or none, is no navigation.
        assert!(chapters(&[dto(0, Some("Seul"), None)], None).is_empty());
        assert!(chapters(&[], Some(1)).is_empty());
        assert!(
            chapters(
                &[
                    dto(0, None, Some("IntroStart")),
                    dto(1, None, Some("IntroEnd"))
                ],
                None
            )
            .is_empty()
        );
    }

    #[test]
    fn long_names_are_cut() {
        let long = "x".repeat(500);
        let found = chapters(
            &[dto(0, Some(&long), None), dto(600_000_000, None, None)],
            None,
        );
        assert_eq!(found[0].name.chars().count(), MAX_NAME_CHARS);
    }
}
