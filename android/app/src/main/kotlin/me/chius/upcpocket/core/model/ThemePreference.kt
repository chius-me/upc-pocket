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

enum class ColorSeed(
    val storageKey: String,
    val displayName: String,
    val swatchArgb: Long,
) {
    OCEAN_BLUE(storageKey = "ocean_blue", displayName = "Pixel 晴海蓝", swatchArgb = 0xFF0B57D0),
    JADE_GREEN(storageKey = "jade_green", displayName = "青翠校园绿", swatchArgb = 0xFF146C2E),
    SUNSET_AMBER(storageKey = "sunset_amber", displayName = "琥珀暖金", swatchArgb = 0xFFB06000),
    IRIS_PURPLE(storageKey = "iris_purple", displayName = "鸢尾紫", swatchArgb = 0xFF65558F),
    ;

    companion object {
        fun fromStorageKey(key: String?): ColorSeed =
            entries.firstOrNull { it.storageKey == key } ?: OCEAN_BLUE
    }
}
