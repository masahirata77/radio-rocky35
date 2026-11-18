use unicode_normalization::{UnicodeNormalization,char::is_combining_mark};
pub struct Station {
    pub id: &'static str,
    pub name: &'static str,
    pub department: &'static str,
    pub search: &'static str,
    pub urls: &'static [&'static str],
}
include!(concat!(env!("OUT_DIR"),"/catalog.rs"));
fn normalize(s:&str)->String{s.nfd().filter(|c|!is_combining_mark(*c)).flat_map(char::to_lowercase).collect()}
pub fn filter(query:&str,department:&str,only:bool,favorites:&[String])->Vec<i32>{
    let q=normalize(query.trim());
    CATALOG.iter().enumerate().filter(|(_,s)|
        (!only||favorites.iter().any(|f|f==s.id))&&
        (department.is_empty()||(department=="unknown"&&s.department.is_empty())||s.department==department)&&
        s.search.contains(&q)
    ).map(|(i,_)|i as i32).collect()
}
#[cfg(test)] mod tests{
    use super::*;
    #[test] fn catalog_and_search(){
        assert_eq!(CATALOG.len(),113); assert_eq!(DEPARTMENTS.len(),18);
        assert!(filter("Océano","",false,&[]).iter().any(|&i|CATALOG[i as usize].name.contains("Océano")));
        assert_eq!(filter("oceano","",false,&[]),filter("OCÉANO","",false,&[]));
        assert_eq!(filter("","unknown",false,&[]).len(),9);
        assert_eq!(filter("","",true,&[CATALOG[0].id.into()]),vec![0]);
        assert!(CATALOG.iter().all(|s|!s.urls.is_empty()));
    }
}
