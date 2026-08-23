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
- **Área de transferência integrada**: `"+y` envia via OSC 52 direto para o
  clipboard do Android; colar usa o botão **PASTE** da barra inferior + `"+p`.
- **Barra de teclas extras**: ESC, TAB, CTRL, ALT, setas, HOME/END, PGUP/PGDN.
  O **botão CTRL é toggle** e a **tecla Diminuir Volume também funciona como Ctrl**
  (segure enquanto digita).
- **Pinça para mudar o tamanho da fonte** (persistente).
- **Treesitter pronto**: parsers de C, Lua, Vim, Vimdoc, Query e Markdown
  (incluindo `markdown_inline`) já vêm no APK.
- **Terminfo embutido** (`TERM=xterm-256color`, truecolor ativo).
- Suporte a **arm64-v8a, armeabi-v7a e x86_64** — funciona em celular, tablet
  e emulador.
- Configuração padrão em `~/.config/nvim/init.lua` (editável dentro do próprio
  app; seu `files/home` sobrevive a atualizações).

## Instalação

Baixe o APK mais recente na página de
[Releases](https://github.com/mexicanbr0auth/AndroVim/releases)
(`AndroVim-release.apk`) e instale normalmente (habilitar "fontes desconhecidas").
Requer Android 7.0 (API 24) ou superior.

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
para o `nativeLibraryDir` — o único local onde apps podem `exec()`.

## Build local

Requisitos: JDK 17, SDK Android (API 34), `python3`, `patchelf`.

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

- `:!cmd` e `:terminal` usam `/system/bin/sh` (mksh/toybox) — suficiente para a
  maioria dos comandos, mas não há coreutils GNU completos.
- Colar via OSC 52 (consulta ao terminal) não é suportado pelo emulador; use o
  botão PASTE ou `"+p` após sincronizar.
- Plugins que exigem `git`, `node`, etc. precisam desses binários — roadmap.

## Roadmap

- [ ] Bundle opcional de busybox + git
- [ ] Temas claro/escuro e cores configuráveis
- [ ] Gestos extras (scrollback por swipe vertical)
- [ ] Suporte a `NVIM_APPNAME` alternativo via menu

## Créditos e licenças

- [Neovim](https://github.com/neovim/neovim) — Apache-2.0
- [Termux terminal-emulator / terminal-view](https://github.com/termux/termux-app) — GPLv3 (via JitPack)
- Binários pré-compilados do [repositório de pacotes Termux](https://github.com/termux/termux-packages)

Este projeto é distribuído sob a **GPL-3.0** (ver `LICENSE`), herdada da
dependência de emulação de terminal do Termux.
