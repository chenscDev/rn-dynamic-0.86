package com.rndynamic.loader

/**
 * 内存中的 Shell 配置（Mock 接口拉取后供 Tab / RNEntryFragment 使用）
 */
object ShellConfigHolder {
    @Volatile
    var config: ShellConfigFile? = null
}
