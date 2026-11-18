package com.rocky35.radio

object RustCore {
    init { System.loadLibrary("rocky_radio") }
    @JvmStatic external fun filterIndices(query: String, department: String, onlyFavorites: Boolean, favorites: Array<String>): IntArray
    @JvmStatic external fun departments(): Array<String>
    @JvmStatic external fun row(index: Int): Array<String>
    @JvmStatic external fun station(id: String): Array<String>
}
