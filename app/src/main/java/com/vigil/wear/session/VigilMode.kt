package com.vigil.wear.session

/**
 * Vigil modes map to simpler health-oriented states for now:
 * passive monitoring for users who are awake but not exercising, and active monitoring while exercising.
 */
enum class VigilMode(val id: String) {
    Passive("passive"),
    Active("active"),
    ;

    companion object {
        fun fromId(id: String): VigilMode? = values().find { it.id == id }
    }
}
