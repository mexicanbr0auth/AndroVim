# AndroVim

**Neovim nativo para Android** — um editor completo rodando em um emulador de terminal
embutido, sem root e sem Termux.

O AndroVim empacota o binário oficial do Neovim compilado para Android (do
repositório de pacotes do [Termux](https://github.com/termux/termux-packages))
junto com todos os arquivos de runtime (`:help` incluído) dentro de um APK
independente. Ao abrir o app, os arquivos são extraídos para o diretório privado
da aplicação e o Neovim inicia imediatamente em um terminal VT100/xterm completo.

[![Android CI](https://github.com/mexicanbr0auth/AndroVim/actions/workflows/android.yml/badge.svg)](https://github.com/mexicanbr0auth/AndroVim/actions/workflows/android.yml)

## Recursos

- **Neovim 0.12+ real**, não uma reimplementação — LuaJIT, LSP, Treesitter,
  `:terminal`, macros, plugins… tudo funciona.
- **Python 3.14, Node.js 24 LTS e Git embutidos no APK** — prontos na primeira
  abertura, sem instalar nada: `:!python`, `:!node`, `:!git` funcionam de cara.
- **LSPs embutidos e pré-configurados**: Pyright (Python), HTML/CSS/JSON
  (vscode-langservers) e TypeScript/JS iniciam sozinhos ao abrir esses arquivos —
  autocomplete, `gd`, `gr`, `K`, `<leader>rn` funcionando de fábrica.
- **Ferramentas**: instalador visual de pacotes com console ao vivo; itens que já
  vêm embutidos aparecem como `[embutido]`.
- **Projetos**: abra uma pasta no nvim com dois toques; **Aparência**: temas.
- **Área de transferência integrada**: `"+y` envia via OSC 52 direto para o
  clipboard do Android; colar usa o botão **PASTE** da barra inferior + `"+p`.
- **Barra de teclas extras**: ESC, TAB, CTRL, ALT, setas, HOME/END, PGUP/PGDN.
  O **botão CTRL é toggle** e a **tecla Diminuir Volume também funciona como Ctrl**
  (segure enquanto digita).
- **Pinça para mudar o tamanho da fonte** (persistente).
- **Treesitter pronto**: parsers de C, Lua, Vim, Vimdoc, Query e Markdown
  (incluindo `markdown_inline`) já vêm no APK.
- **Terminfo embutido** (`TERM=xterm-256color`, truecolor ativo).
- **arm64-v8a** é o alvo principal (celulares modernos); nas outras ABIs o
  editor funciona mas as ferramentas embutidas ficam indisponíveis.
- Configuração padrão em `~/.config/nvim/init.lua` (editável dentro do próprio
  app; seu `files/home` sobrevive a atualizações).

## Instalação

Baixe o APK mais recente na página de
[Releases](https://github.com/mexicanbr0auth/AndroVim/releases)
(`AndroVim-release.apk`) e instale normalmente (habilitar "fontes desconhecidas").
Requer Android 7.0 (API 24) ou superior.

- O APK é grande (~160 MB) porque traz Python, Node.js, Git e os LSPs dentro.
- A primeira abertura extrai tudo e demora alguns segundos a mais — normal.

## Como funciona

```
GitHub Actions ──▶ scripts/fetch-nvim.sh
                     │  baixa nvim + dependências (.deb do repo Termux)
                     │  resolve o fechamento de dependências via índice APT
                     │  renomeia SONAMEs p/ lib*.so (patchelf) e ajusta rpath $ORIGIN
                     ▼
              jniLibs/<abi>/libnvim.so + libs   assets/runtime (+terminfo)
                     │                                  │
                     ▼                                  ▼
        extração p/ nativeLibraryDir          extração p/ filesDir/runtime
                     │                                  │
                     └──────────▶ TerminalSession ◀─────┘
                                       │ (pty + TERM=xterm-256color)
                                       ▼
                            TerminalView (Termux) ──▶ você edita :)
```

Executar binários do diretório de dados é bloqueado desde o Android 10; por isso
os executáveis são empacotados como `lib*.so` no `jniLibs/`, que o sistema extrai
para o `nativeLibraryDir` — o único local onde apps podem `exec()`. O pacote de
ferramentas (Python, Node, Git, apt…) viaja como `libaptdist.so` e é extraído
para `files/usr` na primeira execução; `files/usr/bin` vai no início do `PATH`.

## Build local

Requisitos: JDK 17, SDK Android (API 34), `python3`, `patchelf`, `curl`, `tar`,
`zstd`, `xz`.

```bash
./scripts/fetch-nvim.sh   # baixa e prepara os binários (cacheado em ~/.cache/androvim)
./gradlew :app:assembleDebug
```

O CI faz exatamente isso a cada push — veja `.github/workflows/android.yml`.
Para publicar uma release basta criar uma tag:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

## Limitações conhecidas

- Colar via OSC 52 (consulta ao terminal) não é suportado pelo emulador; use o
  botão PASTE ou `"+p` após sincronizar.
- As ferramentas embutidas são compiladas para aarch64; em emuladores x86_64
  apenas o editor nvim funciona.

## Roadmap

- [x] busybox + git embutidos — ampliado para python/node/git/apt completos
- [ ] Gestos extras (scrollback por swipe vertical)
- [ ] Suporte a `NVIM_APPNAME` alternativo via menu

## Créditos e licenças

- [Neovim](https://github.com/neovim/neovim) — Apache-2.0
- [Termux terminal-emulator / terminal-view](https://github.com/termux/termux-app) — GPLv3 (via JitPack)
- Binários pré-compilados do [repositório de pacotes Termux](https://github.com/termux/termux-packages)

Este projeto é distribuído sob a **GPL-3.0** (ver `LICENSE`), herdada da
dependência de emulação de terminal do Termux.
