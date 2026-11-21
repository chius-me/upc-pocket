package me.chius.upcpocket.core.model

enum class ThemePreference(
    val storageKey: String,
    val displayName: String,
) {
    SYSTEM(storageKey = "system", displayName = "跟随系统"),
    LIGHT(storageKey = "light", displayName = "浅色模式"),
    DARK(storageKey = "dark", displayName = "深色模式"),
    ;

    companion object {
        fun fromStorageKey(key: String?): ThemePreference =
            entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}
