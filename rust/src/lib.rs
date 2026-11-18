mod core;
use jni::{JNIEnv,objects::{JClass,JString,JObjectArray},sys::{jboolean,jint,jintArray,jobjectArray}};
use std::ptr::null_mut;
fn text(env:&mut JNIEnv,s:&JString)->String{env.get_string(s).map(|s|s.into()).unwrap_or_default()}
fn strings(env:&mut JNIEnv,values:impl IntoIterator<Item=impl AsRef<str>>)->jobjectArray{
    let values:Vec<_>=values.into_iter().collect();
    let Ok(arr)=env.new_object_array(values.len() as i32,"java/lang/String",jni::objects::JObject::null())else{return null_mut()};
    for (i,v) in values.iter().enumerate(){let Ok(s)=env.new_string(v.as_ref())else{return null_mut()};if env.set_object_array_element(&arr,i as i32,&s).is_err(){return null_mut()}let _=env.delete_local_ref(s);}
    arr.into_raw()
}
#[no_mangle]
pub extern "system" fn Java_com_rocky35_radio_RustCore_filterIndices(mut env:JNIEnv,_:JClass,query:JString,dept:JString,only:jboolean,favs:JObjectArray)->jintArray{
    let q=text(&mut env,&query);let d=text(&mut env,&dept);
    let mut f=Vec::new();
    if only!=0{for i in 0..env.get_array_length(&favs).unwrap_or(0){if let Ok(o)=env.get_object_array_element(&favs,i){let s=JString::from(o);f.push(text(&mut env,&s));let _=env.delete_local_ref(s);}}}
    let indexes=core::filter(&q,&d,only!=0,&f);
    let Ok(a)=env.new_int_array(indexes.len() as i32)else{return null_mut()};
    if env.set_int_array_region(&a,0,&indexes).is_err(){return null_mut()}a.into_raw()
}
#[no_mangle]
pub extern "system" fn Java_com_rocky35_radio_RustCore_departments(mut env:JNIEnv,_:JClass)->jobjectArray{strings(&mut env,core::DEPARTMENTS.iter().copied())}
#[no_mangle]
pub extern "system" fn Java_com_rocky35_radio_RustCore_row(mut env:JNIEnv,_:JClass,index:jint)->jobjectArray{
    match core::CATALOG.get(index as usize){Some(s)=>strings(&mut env,[s.id,s.name,s.department]),None=>strings(&mut env,[] as [&str;0])}
}
#[no_mangle]
pub extern "system" fn Java_com_rocky35_radio_RustCore_station(mut env:JNIEnv,_:JClass,id:JString)->jobjectArray{
    let id=text(&mut env,&id);
    match core::CATALOG.iter().find(|s|s.id==id){
        Some(s)=>strings(&mut env,std::iter::once(s.name).chain(s.urls.iter().copied())),
        None=>strings(&mut env,[] as [&str;0])
    }
}
