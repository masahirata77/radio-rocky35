package com.rocky35.radio

object RustCore {
    init { System.loadLibrary("rocky_radio") }
    @JvmStatic external fun filter(query: String, department: String, onlyFavorites: Boolean, favorites: String): String
    @JvmStatic external fun departments(): String
}
