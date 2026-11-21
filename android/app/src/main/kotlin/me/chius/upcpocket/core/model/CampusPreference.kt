package me.chius.upcpocket.core.model

enum class CampusPreference(
    val storageKey: String,
    val displayName: String,
) {
    ALL(storageKey = "all", displayName = "全部校区"),
    TANGDAOWAN(storageKey = "tangdaowan", displayName = "唐岛湾"),
    GUZHENKOU(storageKey = "guzhenkou", displayName = "古镇口"),
    ;

    companion object {
        fun fromStorageKey(key: String?): CampusPreference =
            entries.firstOrNull { it.storageKey == key } ?: ALL
    }
}
