package com.rndynamic.loader

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.io.File

/**
 * RN 入口宫格 Tab：根据 Shell 配置展示分包入口（icon + 标题），点击打开原生 RN 容器。
 */
class RNEntryFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val tabId = requireArguments().getString(ARG_TAB_ID).orEmpty()
        val channel = requireArguments().getString(ARG_CHANNEL).orEmpty()
        val configPath = requireArguments().getString(ARG_CONFIG_PATH)

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(requireContext()).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        val grid = GridLayout(requireContext()).apply {
            columnCount = 2
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        try {
            val store = ShellConfigHolder.config?.let { config ->
                config.tabs.firstOrNull { it.id == tabId }?.let { tab ->
                    title.text = tab.title
                    if (tab.visibleEntries.isEmpty()) {
                        grid.addView(emptyHint("暂无可用入口"))
                    } else {
                        tab.visibleEntries.forEach { entry ->
                            grid.addView(createEntryCell(tab, entry, channel, configPath))
                        }
                    }
                    return root
                }
            }

            val storeFromFile = if (configPath.isNullOrBlank()) {
                ShellConfigStore.fromAssets(requireContext(), channel)
            } else {
                ShellConfigStore.fromFile(File(configPath), channel)
            }
            val tab = storeFromFile.tab(tabId)
            title.text = tab.title

            if (tab.visibleEntries.isEmpty()) {
                grid.addView(emptyHint("暂无可用入口"))
                return root
            }

            tab.visibleEntries.forEach { entry ->
                grid.addView(createEntryCell(tab, entry, channel, configPath))
            }
        } catch (error: Exception) {
            title.text = "业务"
            grid.addView(emptyHint("配置加载失败: ${error.message}"))
        }

        return root
    }

    private fun createEntryCell(
        tab: ShellTabConfig,
        entry: ShellEntryConfig,
        channel: String,
        configPath: String?,
    ): View {
        val cell = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(16))
            setBackgroundColor(0xFFF7F7F7.toInt())
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(8), dp(8), dp(8), dp(8))
            }
            setOnClickListener {
                AuthNavigator.saveResumeFromShell(tab.id)
                val intent = RNContainerActivity.intentForBundleKey(
                    context = requireContext(),
                    title = entry.title,
                    bundleKey = entry.key,
                    channel = channel,
                    configPath = configPath,
                    fromNative = "shell-tab-${tab.id}",
                )
                startActivity(intent)
            }
        }

        val iconView = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        ShellIconLoader.loadInto(
            requireContext(),
            entry.icon,
            iconView,
            ShellIconLoader.resolveDrawable(
                requireContext(),
                "ic_entry_home",
                android.R.drawable.ic_menu_view,
            ),
        )

        val label = TextView(requireContext()).apply {
            text = entry.title
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        cell.addView(iconView)
        cell.addView(label)
        return cell
    }

    private fun emptyHint(message: String): TextView {
        return TextView(requireContext()).apply {
            text = message
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            layoutParams = GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(0, 2)
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val ARG_TAB_ID = "arg_tab_id"
        private const val ARG_CHANNEL = "arg_channel"
        private const val ARG_CONFIG_PATH = "arg_config_path"

        fun newInstance(
            tabId: String,
            channel: String,
            configPath: String? = null,
        ): RNEntryFragment {
            return RNEntryFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAB_ID, tabId)
                    putString(ARG_CHANNEL, channel)
                    configPath?.let { putString(ARG_CONFIG_PATH, it) }
                }
            }
        }
    }
}
