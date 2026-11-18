use std::{collections::BTreeSet,fmt::Write,fs,path::PathBuf};
use unicode_normalization::{UnicodeNormalization,char::is_combining_mark};
fn normalize(s:&str)->String{s.nfd().filter(|c|!is_combining_mark(*c)).flat_map(char::to_lowercase).collect()}
fn main(){
    println!("cargo:rerun-if-changed=../stations.json");
    let source=fs::read_to_string("../stations.json").unwrap();
    let arr:Vec<serde_json::Value>=serde_json::from_str(&source).unwrap();
    let mut out=String::from("pub static CATALOG: &[Station] = &[\n");
    let mut departments=BTreeSet::new();
    let mut has_unknown=false;
    for s in &arr{
        let id=s["id"].as_str().unwrap();let name=s["name"].as_str().unwrap();
        let dept=s["department"].as_str().unwrap_or("");
        if dept.is_empty(){has_unknown=true}else{departments.insert(dept);}
        let tags=s["tags"].as_str().unwrap_or("");
        let key=normalize(&format!("{name} {dept} {tags}"));
        let mut urls=Vec::new();
        for u in s["streams"].as_array().into_iter().flatten().chain([&s["url"],&s["original"]]){
            if let Some(u)=u.as_str(){if (u.starts_with("http://")||u.starts_with("https://"))&&!urls.contains(&u){urls.push(u)}}
        }
        writeln!(out,"Station {{ id: {id:?}, name: {name:?}, department: {dept:?}, search: {key:?}, urls: &{urls:?} }},").unwrap();
    }
    out.push_str("];\n");
    let mut depts:Vec<_>=departments.into_iter().collect();depts.sort_by_key(|s|normalize(s));
    let mut choices=vec![""];choices.extend(depts);if has_unknown{choices.push("unknown")}
    writeln!(out,"pub static DEPARTMENTS: &[&str] = &{choices:?};").unwrap();
    fs::write(PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("catalog.rs"),out).unwrap();
}
