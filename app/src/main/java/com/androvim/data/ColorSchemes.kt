package com.androvim.data

import com.termux.terminal.TextStyle
import com.termux.terminal.TerminalEmulator

/** A terminal color scheme: 16 ANSI colors + foreground/background/cursor. */
data class ColorScheme(
    val id: String,
    val label: String,
    val bg: Int,
    val fg: Int,
    val cursor: Int,
    val palette: IntArray, // 16 entries, ANSI 0..15
) {
    override fun equals(other: Any?) = other is ColorScheme && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        private fun rgb(hex: Long): Int = (hex or 0xFF000000).toInt()

        val ALL = listOf(
            ColorScheme(
                "default", "Padrão",
                bg = rgb(0x10141A), fg = rgb(0xD5DAE0), cursor = rgb(0xD5DAE0),
                palette = ansi(
                    0x10141A, 0xCD6A6A, 0x7FBF7F, 0xD9C97C, 0x7AA6C8, 0xC79AD1,
                    0x74B8B8, 0xB8C4D0, 0x5A6672, 0xE08C8C, 0x98D498, 0xEDE093,
                    0x9EC4E8, 0xDDBBE8, 0x93D0D0, 0xE6EDF3,
                ),
            ),
            ColorScheme(
                "dracula", "Dracula",
                bg = rgb(0x282A36), fg = rgb(0xF8F8F2), cursor = rgb(0xF8F8F2),
                palette = ansi(
                    0x21222C, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6,
                    0x8BE9FD, 0xF8F8F2, 0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5,
                    0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF,
                ),
            ),
            ColorScheme(
                "nord", "Nord",
                bg = rgb(0x2E3440), fg = rgb(0xD8DEE9), cursor = rgb(0xD8DEE9),
                palette = ansi(
                    0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD,
                    0x88C0D0, 0xE5E9F0, 0x4C566A, 0xBF616A, 0xA3BE8C, 0xEBCB8B,
                    0x81A1C1, 0xB48EAD, 0x8FBCBB, 0xECEFF4,
                ),
            ),
            ColorScheme(
                "gruvbox", "Gruvbox Dark",
                bg = rgb(0x282828), fg = rgb(0xEBDBB2), cursor = rgb(0xEBDBB2),
                palette = ansi(
                    0x282828, 0xCC241D, 0x98971A, 0xD79921, 0x458588, 0xB16286,
                    0x689D6A, 0xA89984, 0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F,
                    0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2,
                ),
            ),
            ColorScheme(
                "solarized", "Solarized Dark",
                bg = rgb(0x002B36), fg = rgb(0x93A1A1), cursor = rgb(0x93A1A1),
                palette = ansi(
                    0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682,
                    0x2AA198, 0xEEE8D5, 0x002B36, 0xCB4B16, 0x586E75, 0x657B83,
                    0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
                ),
            ),
            ColorScheme(
                "monokai", "Monokai",
                bg = rgb(0x272822), fg = rgb(0xF8F8F2), cursor = rgb(0xF8F8F2),
                palette = ansi(
                    0x272822, 0xF92672, 0xA6E22E, 0xF4BF75, 0x66D9EF, 0xAE81FF,
                    0xA1EFE4, 0xF8F8F2, 0x75715E, 0xF92672, 0xA6E22E, 0xF4BF75,
                    0x66D9EF, 0xAE81FF, 0xA1EFE4, 0xF9F8F5,
                ),
            ),
        )

        fun byId(id: String): ColorScheme = ALL.firstOrNull { it.id == id } ?: ALL.first()

        /** Write the scheme into the emulator's palette (indices 0..15, fg=256, bg=257, cursor=258). */
        fun applyTo(emulator: TerminalEmulator, scheme: ColorScheme) {
            val colors = emulator.mColors.mCurrentColors
            scheme.palette.forEachIndexed { i, c -> if (i < 256) colors[i] = c }
            colors[TextStyle.COLOR_INDEX_FOREGROUND] = scheme.fg
            colors[TextStyle.COLOR_INDEX_BACKGROUND] = scheme.bg
            colors[TextStyle.COLOR_INDEX_CURSOR] = scheme.cursor
        }

        private fun ansi(vararg hex: Long): IntArray = IntArray(hex.size) { i -> rgb(hex[i]) }
    }
}
