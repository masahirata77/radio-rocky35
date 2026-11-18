use serde::{Deserialize, Serialize};
use std::{collections::HashSet, sync::OnceLock};
use unicode_normalization::{UnicodeNormalization, char::is_combining_mark};
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Station {
    pub id: String,
    pub name: String,
    pub department: Option<String>,
    #[serde(default)] pub tags: String,
}
static CATALOG: OnceLock<Vec<Station>> = OnceLock::new();
pub fn catalog() -> &'static [Station] {
    CATALOG.get_or_init(|| serde_json::from_str(include_str!("../../stations.json")).expect("embedded catalog"))
}
fn normalize(s: &str) -> String {
    s.nfd().filter(|c| !is_combining_mark(*c)).flat_map(char::to_lowercase).collect()
}
pub fn filter(query: &str, department: &str, favorites_only: bool, favorites: &HashSet<String>) -> Vec<Station> {
    let query = normalize(query.trim());
    catalog().iter().filter(|s| {
        (!favorites_only || favorites.contains(&s.id)) &&
        (department.is_empty() || (department == "unknown" && s.department.is_none()) || s.department.as_deref() == Some(department)) &&
        normalize(&format!("{} {} {}", s.name, s.department.as_deref().unwrap_or(""), s.tags)).contains(&query)
    }).cloned().collect()
}
pub fn departments() -> Vec<String> {
    let mut ds: Vec<_> = catalog().iter().filter_map(|s| s.department.clone()).collect();
    ds.sort_by_key(|s|normalize(s)); ds.dedup(); ds
}
#[cfg(test)] mod tests {
 use super::*;
 #[test] fn catalog_and_search() {
    assert_eq!(catalog().len(),113);assert_eq!(departments().len(),16);
    assert!(filter("oceano","",false,&HashSet::new()).iter().any(|s|s.name.contains("Océano")));
    assert_eq!(filter("","unknown",false,&HashSet::new()).len(),9);
    let mut f=HashSet::new();f.insert(catalog()[0].id.clone());assert_eq!(filter("","",true,&f).len(),1);
 }
}
