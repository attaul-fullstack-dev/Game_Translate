package com.example

internal enum class TranslationModel(
    val displayName: String,
    val description: String,
    val sizeLabel: String = "~30 MB",
) {
    ENGLISH(
        displayName = "Inggris",
        description = "Model Bahasa Inggris",
    ),
    INDONESIAN(
        displayName = "Indonesia",
        description = "Model Bahasa Indonesia",
    ),
}

internal enum class ModelDownloadPhase {
    IDLE,
    CHECKING,
    DOWNLOADING,
    VERIFYING,
    READY,
    FAILED,
}

internal data class ModelDownloadProgress(
    val phase: ModelDownloadPhase = ModelDownloadPhase.IDLE,
    val currentModel: TranslationModel? = null,
    val readyModels: Set<TranslationModel> = emptySet(),
) {
    val completedCount: Int
        get() = readyModels.size

    val totalCount: Int
        get() = TranslationModel.entries.size

    val allReady: Boolean
        get() = completedCount == totalCount
}

internal const val SLOW_MODEL_DOWNLOAD_SECONDS = 180L

internal fun formatElapsedTime(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3_600
    val minutes = (safeSeconds % 3_600) / 60
    val seconds = safeSeconds % 60

    return if (hours > 0) {
        "${hours.toString().padStart(2, '0')}:" +
            "${minutes.toString().padStart(2, '0')}:" +
            seconds.toString().padStart(2, '0')
    } else {
        "${minutes.toString().padStart(2, '0')}:" +
            seconds.toString().padStart(2, '0')
    }
}
