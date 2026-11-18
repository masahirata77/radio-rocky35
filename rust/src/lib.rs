mod core;
use jni::{JNIEnv, objects::{JClass,JString},sys::{jboolean,jstring}};
use std::collections::HashSet;
fn text(env:&mut JNIEnv,s:&JString)->String {env.get_string(s).map(|s|s.into()).unwrap_or_default()}
#[no_mangle]
pub extern "system" fn Java_com_rocky35_radio_RustCore_filter(mut env:JNIEnv,_:JClass,query:JString,department:JString,only:jboolean,favs:JString)->jstring{
    let q=text(&mut env,&query);let d=text(&mut env,&department);let f=text(&mut env,&favs);
    let favorites:HashSet<String>=serde_json::from_str(&f).unwrap_or_default();
    let result=serde_json::to_string(&core::filter(&q,&d,only!=0,&favorites)).unwrap_or_else(|_|"[]".into());
    env.new_string(result).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_rocky35_radio_RustCore_departments(env:JNIEnv,_:JClass)->jstring{
    env.new_string(serde_json::to_string(&core::departments()).unwrap()).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())
}
