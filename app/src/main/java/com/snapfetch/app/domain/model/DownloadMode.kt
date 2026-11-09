package com.snapfetch.app.domain.model

enum class DownloadMode(
    val title: String,
    val description: String,
    val isDefault: Boolean
) {
    FAST(
        title = "⚡ Modo Rápido (1-Clic)",
        description = "Descarga directa en MP4 1080p sin preguntas",
        isDefault = true
    ),
    ADVANCED(
        title = "⚙️ Modo Avanzado / Personalizado",
        description = "Preguntar siempre calidad (4K, 1080p, MP3) antes de guardar",
        isDefault = false
    );

    companion object {
        fun fromName(name: String?): DownloadMode {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: FAST
        }
    }
}
