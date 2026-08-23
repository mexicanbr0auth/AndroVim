package com.androvim.data

/** Curated installable system packages (Termux .debs) offered in Ferramentas. */
data class ToolPackage(
    val pkgName: String,
    val label: String,
    val description: String,
)

object ToolCatalog {
    val ALL = listOf(
        ToolPackage("git", "Git", "Controle de versão — necessário p/ instalar plugins do GitHub."),
        ToolPackage("python", "Python 3", "Interpretador Python + pip (:!python, providers LSP)."),
        ToolPackage("nodejs-lts", "Node.js", "Runtime JavaScript LTS, embutido no app (copilot-language-server, Mason etc)."),
        ToolPackage("clang", "Clang", "Compilador C/C++ — usado pelo nvim-treesitter p/ parsers."),
        ToolPackage("openssh", "OpenSSH", "ssh/scp/sftp para editar projetos remotos."),
        ToolPackage("ripgrep", "Ripgrep", "grep ultrarrápido — acelera o Telescope live_grep."),
        ToolPackage("fzf", "fzf", "Fuzzy finder universal de linha de comando."),
        ToolPackage("jq", "jq", "Processador JSON pela linha de comando."),
        ToolPackage("tmux", "tmux", "Multiplexador de terminal dentro do :terminal."),
        ToolPackage("lazygit", "Lazygit", "Interface TUI para o git."),
    )
}
