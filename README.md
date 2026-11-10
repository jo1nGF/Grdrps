# Gf Drps

Instrumento de samples multitoque para Android, no estilo dos apps de manipulação direta de forma de onda.
Feito para rodar no **Galaxy S10** (landscape, 48 kHz, áudio de baixa latência), mas funciona em qualquer Android 8.0+ (API 26).

Sem dependências externas: só Java e o SDK do Android.

## Como instalar no S10

1. No GitHub, aba **Actions** → rode **Build Gf Drps APK** (ou dê push na branch `main`).
2. Baixe o artefato **GfDrps-debug-apk** (um `.zip` com `app-debug.apk`).
3. Copie o APK para o celular e abra (permita "instalar apps desconhecidos"), ou use `adb install app-debug.apk`.

Para compilar localmente: `./gradlew assembleDebug` (precisa do Android SDK e JDK 17).

## O que tem

| Recurso | Como funciona |
|---|---|
| 6 slots de sample | Cada slot tem até 8 vozes, 5 efeitos próprios e volume/attack/release/pan |
| **Slicer** | 4/8/16/32 fatias ou **fatias manuais** (botão de marcador: toque cria, arraste move, toque no marcador apaga, segurar volta à grade) e **detecção automática de batidas** (estrela); altura do toque = velocity |
| **Slicer auto/Q** | ▶ toca as fatias sozinho (🎲 embaralha); Q quantiza os toques na grade do BPM |
| **Latch** | O som continua ao soltar o dedo e ao trocar de modo |
| **Looper** | Dois dedos definem o loop com duração exata; **cruzar os dedos toca em reverse**; 4/8/16/32 prende os marcadores na grade; mover juntos desloca a janela |
| **Bow** | Grão do som em loop sob o dedo (textura/pad); altura muda o tamanho do grão |
| **Tape** | Arraste para direita acelera, esquerda inverte; soltar = tape stop |
| **Scratch** | O dedo arrasta o playhead (largura da tela = 0,25–2 s de áudio conforme a altura); movimento suave, dedo parado = silêncio |
| **Arpeggiator** | Toque várias fatias: o padrão toca nas fatias sob os dedos, travado no BPM. Direção UP/DN/U-D/RND/ORD (botão rosa), taxa 1/1–1/64 com tercinas (botão amarelo: toque ou deslize), usa o ADSR do slot |
| **Keyboard** | 24 teclas cromáticas; ∞ sustenta; curva = legato (glide) |
| **Loop Player** | Loop travado no BPM do projeto; toque liga/desliga (ou gate) |
| Efeitos XY | Distortion, **Filter LP/HP** (toque de novo no ícone do filtro alterna LP/HP e o ícone muda; a curva real aparece no pad), Amp Mod, Delay de 10 ms a 4 s (1 ms nas faixas curtas = filtro pente) com botão sync de BPM, **Reverb "cloud" (plate de Dattorro)**, cauda longa e sem metálico, decay de 0,6 a 14 s (x) e wet (y) que some por completo ao soltar; "M" aplica no master; "hold" trava o efeito ligado. Entrada e saída dos efeitos são suavizadas (sem estalos) |
| Gravador de gestos | ● arma a gravação: começa no primeiro toque (waveform ou pad de efeito), 1–128 compassos, repete em loop e é salvo no projeto; troque o modo durante o loop para mudar como ele soa |
| Resample | Botão de círculos duplos grava o mix final num slot; microfone grava no slot |
| Importar | WAV, AIFF (direto) e MP3/M4A/OGG/FLAC (decodificador do Android) |
| Projetos | Menu **Gf** → salvar/abrir (samples + parâmetros) |
| MIDI | USB/Bluetooth: notas tocam o slot selecionado; **MIDI Learn** para volumes, tempo e efeito X/Y |

## Controles da tela

- **Gf**: menu (projetos, MIDI). **Metrônomo**, **BPM** (arraste para cima/baixo, ou toque no ritmo), volume master, **■** corta todas as vozes, **?** ajuda.
- Linha de modos: os 8 ícones. **Pasta** carrega arquivo no slot; **◀|** inverte o sample (toque de novo restaura). O modo gate fica no menu do slot (segure o slot).
- Sliders: volume · attack · decay · sustain · release · pan.
- Botões `0 4 8 16 32`: fatias do Slicer / passos por compasso do Arpeggiator / grade dos marcadores do Looper (0 = livre). Ao lado: estrela (auto-fatiar, no Slicer) ou dado (aleatório, no Arp) e o botão de editar fatias. `-12 -1 0 +1 +12`: transposição.
- Linha de gestos: ● grava, número = compassos (toque para mudar), ✕ apaga, ∞ sustenta.
- Rodapé: ▶/■ transporte, os 6 slots (toque seleciona, **segure** para o menu), microfone, mix.

## Estrutura

```
app/src/main/java/com/gfdrps/core      motor de áudio (Java puro, testável no PC)
app/src/main/java/com/gfdrps/android   AudioTrack, microfone, MIDI, importação, projetos, interface
test/SelfTest.java                     teste do motor (roda também no GitHub Actions)
```

Para rodar o teste do motor no PC:

```
javac -d out app/src/main/java/com/gfdrps/core/*.java test/SelfTest.java && java -cp out SelfTest
```

## Fora do escopo desta versão

Ableton Link (precisa do SDK nativo em C++), Audiobus e Inter-App Audio (só existem no iOS), plugin/AUv3.
