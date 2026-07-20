package com.rndynamic.loader

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * @deprecated 请使用 [RNContainerActivity]
 */
@Deprecated("请使用 RNContainerActivity")
class RNDebugMountActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(this, RNContainerActivity::class.java).apply {
            putExtra(RNContainerActivity.EXTRA_MODE, RNContainerActivity.MODE_LOCAL_PATHS)
            putExtra(RNContainerActivity.EXTRA_TITLE, intent.getStringExtra(EXTRA_MODULE_NAME))
            putExtra(RNContainerActivity.EXTRA_MODULE_NAME, intent.getStringExtra(EXTRA_MODULE_NAME))
            putExtra(RNContainerActivity.EXTRA_PAGE_PATH, intent.getStringExtra(EXTRA_PAGE_PATH))
            putExtra(RNContainerActivity.EXTRA_COMMON_PATH, intent.getStringExtra(EXTRA_COMMON_PATH))
            putExtra(RNContainerActivity.EXTRA_CHANNEL, intent.getStringExtra(EXTRA_CHANNEL))
            putExtra(RNContainerActivity.EXTRA_FROM_NATIVE, intent.getStringExtra(EXTRA_FROM_NATIVE))
        }
        startActivity(forward)
        finish()
    }

    companion object {
        const val EXTRA_MODULE_NAME = "rn_debug_module_name"
        const val EXTRA_PAGE_PATH = "rn_debug_page_path"
        const val EXTRA_COMMON_PATH = "rn_debug_common_path"
        const val EXTRA_CHANNEL = "rn_debug_channel"
        const val EXTRA_FROM_NATIVE = "rn_debug_from_native"
    }
}
