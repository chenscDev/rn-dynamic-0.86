package com.rndynamic.loader

/**
 * Shell 导航配置模型（与 packages/schema 对齐）
 */
data class ShellEntryConfig(
    val key: String,
    val title: String,
    val icon: String? = null,
    val visible: Boolean = true,
    val order: Int = 0,
)

data class ShellTabConfig(
    val id: String,
    val title: String,
    val type: String,
    val nativeKey: String? = null,
    val icon: String? = null,
    val visible: Boolean = true,
    val order: Int = 0,
    val entries: List<ShellEntryConfig> = emptyList(),
    val bundleKey: String? = null,
) {
    val visibleEntries: List<ShellEntryConfig>
        get() = entries.filter { it.visible }.sortedBy { it.order }
}

data class ShellConfigFile(
    val rnVersion: String,
    val channel: String,
    val updatedAt: String,
    val tabs: List<ShellTabConfig>,
) {
    val visibleTabs: List<ShellTabConfig>
        get() = tabs.filter { it.visible }.sortedBy { it.order }
}
