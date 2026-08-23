package com.androvim.data

/** A Neovim plugin that can be installed into ~/.local/share/nvim/site/pack/androvim/start */
data class NvimPlugin(
    val id: String,
    val repo: String, // "user/repo" shorthand for github
    val label: String,
    val description: String,
    val dependsOn: List<String> = emptyList(),
    val configLua: String? = null, // snippet appended to managed config on install
)

object PluginCatalog {

    const val PACK_DIR = "pack/androvim/start"

    val ALL = listOf(
        NvimPlugin(
            "plenary", "nvim-lua/plenary.nvim", "plenary.nvim",
            "Biblioteca de funções Lua usada por telescope e vários outros plugins.",
            configLua = "-- plenary não requer configuração",
        ),
        NvimPlugin(
            "telescope", "nvim-telescope/telescope.nvim", "Telescope",
            "Fuzzy finder para arquivos, buffers, grep e muito mais (<leader>ff).",
            dependsOn = listOf("plenary"),
            configLua = """
                require("telescope").setup({})
                vim.keymap.set("n", "<leader>ff", "<cmd>Telescope find_files<cr>")
                vim.keymap.set("n", "<leader>fg", "<cmd>Telescope live_grep<cr>")
                vim.keymap.set("n", "<leader>fb", "<cmd>Telescope buffers<cr>")
            """.trimIndent(),
        ),
        NvimPlugin(
            "devicons", "nvim-tree/nvim-web-devicons", "Web Devicons",
            "Ícones de arquivos para a barra de status e exploradores.",
        ),
        NvimPlugin(
            "lualine", "nvim-lualine/lualine.nvim", "Lualine",
            "Barra de status escrita em Lua, rápida e configurável.",
            configLua = """
                require("lualine").setup({
                  options = { theme = "auto", icons_enabled = true },
                })
            """.trimIndent(),
        ),
        NvimPlugin(
            "gitsigns", "lewis6991/gitsigns.nvim", "Gitsigns",
            "Sinais de git na gutter (+/-/~) com hunk navigation. Requer o pacote 'git'.",
            configLua = """
                require("gitsigns").setup({})
            """.trimIndent(),
        ),
        NvimPlugin(
            "which-key", "folke/which-key.nvim", "Which Key",
            "Mostra os possíveis mapeamentos ao pressionar <leader>. Indispensável.",
            configLua = """
                require("which-key").setup({})
            """.trimIndent(),
        ),
        NvimPlugin(
            "comment", "numToStr/Comment.nvim", "Comment",
            "Comentar linhas/blocos com gcc e gc (operação de operador).",
            configLua = """
                require("Comment").setup({})
            """.trimIndent(),
        ),
        NvimPlugin(
            "autopairs", "windwp/nvim-autopairs", "Autopairs",
            "Fecha parênteses, chaves e aspas automaticamente em insert mode.",
            configLua = """
                require("nvim-autopairs").setup({})
            """.trimIndent(),
        ),
        NvimPlugin(
            "catppuccin", "catppuccin/nvim", "Catppuccin",
            "Tema pastel popular (latte/frappé/macchiato/mocha).",
            configLua = """
                require("catppuccin").setup({ flavour = "mocha" })
                vim.cmd.colorscheme("catppuccin")
            """.trimIndent(),
        ),
        NvimPlugin(
            "tokyonight", "folke/tokyonight.nvim", "Tokyo Night",
            "Tema escuro limpo inspirado nas luzes de Tóquio.",
            configLua = """
                require("tokyonight").setup({ style = "storm" })
                vim.cmd.colorscheme("tokyonight")
            """.trimIndent(),
        ),
        NvimPlugin(
            "trouble", "folke/trouble.nvim", "Trouble",
            "Lista bonita de diagnósticos LSP, referências e quickfix.",
            dependsOn = listOf("devicons"),
            configLua = """
                require("trouble").setup({})
                vim.keymap.set("n", "<leader>xx", "<cmd>TroubleToggle<cr>")
            """.trimIndent(),
        ),
        NvimPlugin(
            "treesitter", "nvim-treesitter/nvim-treesitter", "Treesitter (highlight)",
            "Realce sintático avançado. Para compilar novos parsers instale o 'clang' em Ferramentas.",
            configLua = """
                require("nvim-treesitter.configs").setup({
                  highlight = { enable = true },
                })
            """.trimIndent(),
        ),
        NvimPlugin(
            "cmp", "hrsh7th/nvim-cmp", "Autocomplete (nvim-cmp)",
            "Motor de autocomplete extensível. Fontes básicas incluídas.",
            dependsOn = listOf("plenary"),
            configLua = """
                local cmp = require("cmp")
                cmp.setup({
                  mapping = cmp.mapping.preset.insert({}),
                  sources = cmp.config.sources({
                    { name = "nvim_lsp" }, { name = "buffer" },
                  }),
                })
            """.trimIndent(),
        ),
        NvimPlugin(
            "surround", "kylechui/nvim-surround", "Surround",
            "Manipula pares de delimitadores (aspas, parênteses, tags) com ys/cs/ds.",
            configLua = """
                require("nvim-surround").setup({})
            """.trimIndent(),
        ),
        NvimPlugin(
            "undotree", "mbbill/undotree", "Undotree",
            "Visualiza o histórico de undo em árvore (:UndotreeToggle).",
            configLua = """
                vim.keymap.set("n", "<leader>u", "<cmd>UndotreeToggle<cr>")
            """.trimIndent(),
        ),
        NvimPlugin(
            "harpoon", "ThePrimeagen/harpoon", "Harpoon",
            "Marque e alterne rapidamente entre seus arquivos favoritos.",
            dependsOn = listOf("plenary"),
            configLua = """
                local mark = require("harpoon.mark")
                local ui = require("harpoon.ui")
                vim.keymap.set("n", "<leader>a", mark.add_file)
                vim.keymap.set("n", "<leader>e", ui.toggle_quick_menu)
            """.trimIndent(),
        ),
    )

    fun byId(id: String): NvimPlugin? = ALL.firstOrNull { it.id == id }
}
