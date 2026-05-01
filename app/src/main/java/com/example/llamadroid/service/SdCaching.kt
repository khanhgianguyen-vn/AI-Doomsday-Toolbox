package com.example.llamadroid.service

enum class SdCacheMode(val cliName: String) {
    UCACHE("ucache"),
    EASYCACHE("easycache"),
    DBCACHE("dbcache"),
    TAYLORSEER("taylorseer"),
    CACHE_DIT("cache-dit"),
    SPECTRUM("spectrum");

    companion object {
        fun fromStoredValue(value: String?): SdCacheMode? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.cliName.equals(value, ignoreCase = true)
            }
        }
    }
}

enum class SdCacheScmPolicy(val cliName: String) {
    DYNAMIC("dynamic"),
    STATIC("static");

    companion object {
        fun fromStoredValue(value: String?): SdCacheScmPolicy? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.cliName.equals(value, ignoreCase = true)
            }
        }
    }
}
