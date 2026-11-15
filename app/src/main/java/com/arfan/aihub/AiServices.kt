package com.arfan.aihub

/**
 * One AI service shown as a card on the Home screen.
 *
 * @param id          Stable key used for the per-service desktop-mode preference.
 * @param label       Short name shown on the card and in the chat toolbar.
 * @param url         Start URL loaded into the WebView when the card is tapped.
 * @param role        Short role tag shown under the name on the card.
 * @param accent      Accent colour (ARGB int) for the card's initial circle.
 * @param initial     Letter shown inside the accent circle.
 * @param desktopMode Default rendering mode for this service; `true` requests
 *                    the desktop layout (desktop UA + forced 1280px viewport).
 *                    The user can override this per service with the toolbar
 *                    toggle; the override is stored in SharedPreferences.
 */
data class AiService(
    val id: String,
    val label: String,
    val url: String,
    val role: String,
    val accent: Int,
    val initial: String,
    val desktopMode: Boolean
)

/**
 * Central configuration list for every service in the app. Add or remove
 * entries here to change the Home grid and the toolbar quick-switch dropdown —
 * nothing else needs to be touched.
 */
object AiServices {

    val TABS: List<AiService> = listOf(
        AiService(
            id = "qwen",
            label = "Qwen",
            url = "https://chat.qwen.ai",
            role = "Planner",
            accent = 0xFF9B7DFF.toInt(),
            initial = "Q",
            desktopMode = false
        ),
        AiService(
            id = "coder",
            label = "Coder",
            url = "https://coder.qwen.ai",
            role = "Builder",
            accent = 0xFF22C55E.toInt(),
            initial = "C",
            // Qwen Coder is a full IDE, so it opens in desktop mode by default.
            desktopMode = true
        ),
        AiService(
            id = "arena",
            label = "Arena",
            url = "https://arena.ai/agent",
            role = "Builder/agent",
            accent = 0xFF38BDF8.toInt(),
            initial = "A",
            // Arena's agent workspace is a wide, desktop-first layout.
            desktopMode = true
        ),
        AiService(
            id = "gemini",
            label = "Gemini",
            url = "https://gemini.google.com",
            role = "Design/Language",
            accent = 0xFF4285F4.toInt(),
            initial = "G",
            desktopMode = false
        ),
        AiService(
            id = "deepseek",
            label = "DeepSeek",
            url = "https://chat.deepseek.com",
            role = "Code review",
            accent = 0xFF06B6D4.toInt(),
            initial = "D",
            desktopMode = false
        ),
        AiService(
            id = "meta",
            label = "Meta",
            url = "https://www.meta.ai",
            role = "Logic review",
            accent = 0xFFE1306C.toInt(),
            initial = "M",
            desktopMode = false
        ),
        AiService(
            id = "zai",
            label = "Zai",
            url = "https://chat.z.ai",
            role = "Senior review",
            accent = 0xFFF59E0B.toInt(),
            initial = "Z",
            desktopMode = false
        ),
        AiService(
            id = "minimax",
            label = "MiniMax",
            url = "https://agent.minimax.io",
            role = "Optional",
            accent = 0xFFEF4444.toInt(),
            initial = "M",
            desktopMode = false
        ),
        AiService(
            id = "mimo",
            label = "MiMo",
            url = "https://aistudio.xiaomimimo.com/#/c",
            role = "General",
            accent = 0xFF14B8A6.toInt(),
            initial = "M",
            desktopMode = false
        )
    )

    /** Look up a service by its stable [AiService.id]. */
    fun findById(id: String?): AiService? = TABS.firstOrNull { it.id == id }
}
