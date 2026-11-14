package com.arfan.aihub

/**
 * One AI chat service shown as a bottom-navigation tab.
 *
 * @param label Short text shown on the tab.
 * @param url   Start URL loaded into the WebView when the tab becomes active.
 */
data class AiService(
    val label: String,
    val url: String
)

/**
 * Central configuration list for all tabs. Add or remove entries here to
 * change the app's tab bar — nothing else needs to be touched.
 */
object AiServices {
    val TABS: List<AiService> = listOf(
        AiService("Qwen",     "https://chat.qwen.ai"),
        AiService("Gemini",   "https://gemini.google.com"),
        AiService("DeepSeek", "https://chat.deepseek.com"),
        AiService("Zai",      "https://chat.z.ai"),
        AiService("Meta",     "https://www.meta.ai"),
        AiService("MiniMax",  "https://agent.minimax.io"),
        AiService("MiMo",     "https://aistudio.xiaomimimo.com/#/c")
    )
}
