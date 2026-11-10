# Changelog

## 1.6 (build 7)
- **Slicer + legato**: deslizar o dedo entre fatias faz glissando (a nota continua e sobe/desce um semitom por fatia) em vez de retocar.
- **Delay**: a grade de sync usa a mesma divisão de tempo do resto do app (1/64 a 1 compasso, com tercinas) e há a opção "seguir a grade do slot" (taxa do Arp / Q).
- **Amp mod = gate** com 7 formas de onda (seno, gate, serra, rampa, triângulo, trance, aleatório) e taxa livre ou sincronizada ao BPM.
- **Duplo toque em cada efeito** abre um submenu com as opções dele (tipo do filtro, forma do amp mod, sync/grade/filtro do delay, filtro do reverb, centralizar pad).
- **Master**: compressor e limiter (menu Gf → "Master: compressor e limiter"); salvos no projeto.
- **Gestos**: apertar ● de novo não perde mais o gesto (finaliza e mantém); ● com o gesto tocando faz **overdub** (soma uma camada; undo remove); menu (segure ●): **presets de gesto** (salvar / carregar em qualquer track / apagar).
- **Importar áudio**: escolha o início e o tamanho do trecho (segundos ou min:seg); só o trecho é decodificado.
- **Loop Player**: o dedo é a agulha — tocar ou arrastar leva o playhead para aquele ponto.
- **Sliders** com ícones (volume, attack, decay, sustain, release, pan, grão).
- **Zoom**: deslizar o dedo para cima/baixo no botão move a faixa horizontalmente.
- **Latência**: buffer de áudio começa com apenas 1 burst e cresce sozinho se houver underrun (verificação mais rápida).
- **Looper**: a grade (4/8/16/32) agora é relativa ao trecho visível — com zoom em faixas longas a distância entre os dedos é respeitada.
- **Arp**: o botão de marcador volta a aparecer e edita as fatias; a taxa passou a ser o botão amarelo (Q).
- **Transposição**: deslizar no valor = pitch bend (direita sobe, esquerda desce, volta ao soltar); segurar 2 s abre o campo para digitar o pitch (aceita decimais).
- **Layout**: botões que não valem para o modo atual desaparecem (em vez de ficarem esmaecidos).

## 1.5 (build 6)
- **Gestos reproduzidos aparecem na tela**: os dedos gravados viram anéis vermelhos (com linha vertical) na waveform e a posição do efeito gravado é mostrada no pad XY com um anel vermelho.
- **Slicer**: a fatia tocada brilha em branco, como no Arpeggiator (também quando vem de auto-play ou de toque quantizado).
- **Bow**: o slider de tamanho do grão agora é fluido (o tamanho desliza em tempo real, inclusive com a nota segurada, e a altura do dedo / volume não dá mais degraus).
- **Looper**: corrigido — cada par de dedos faz um loop próprio (1+2, 3+4, 5+6, 7+8): até 4 loops ao mesmo tempo, cada um com seu reverse, e soltar um par só encerra aquele loop. A tela pinta uma região para cada par.
- **Refazer (redo)** ao lado do desfazer; uma edição nova limpa a pilha de refazer.
- **Deslizar nos botões**: o zoom aumenta/diminui continuamente ao deslizar sobre o botão; a taxa do Arp, a grade do Q e as fatias (0 4 6 8 12 16 24 32 48 64) mudam ao deslizar na horizontal.
- **Mais subdivisões**: taxa/grade 1/1, 1/2, 1/4, 1/8, 1/16, 1/32, 1/64 e tercinas (1/2T, 1/4T, 1/8T, 1/16T, 1/32T).
- **LP/HP no Delay e no Reverb**: botão LP/HP (toque = desligado → passa-baixas → passa-altas; deslize = frequência de corte, 40 Hz–18 kHz). No Delay o filtro age também na realimentação (repetições mais escuras ou mais finas). Salvo no projeto.
- **Mais tracks**: botão "+" no rodapé adiciona tracks (até 12); a barra de tracks rola. O projeto salva o número de tracks.
- **Tela de gravação**: ao gravar (microfone ou mix) aparece uma tela com a waveform sendo gravada, medidor de nível, tempo e botões SALVAR / CANCELAR. Para o resample do mix a barra fica só sobre o rodapé, então dá para continuar tocando.
- **Ajuda**: a legenda agora ajusta colunas e tamanho do texto para caber, então todo número da tela tem sua explicação.

## 1.4 (build 5)
- **Looper**: botão P-P liga o ping-pong (o loop vai e volta entre os dedos, sem saltos).
- **Bow**: altura do dedo = volume; slider azul novo = tamanho do grão (de granular a pad); no máximo 4 notas.
- **Tape / Scratch**: com 4/8/16/32 selecionado a velocidade (Tape) e a posição (Scratch) andam em degraus.
- **Keyboard**: botão de marcador edita início e fim do sample; segure para voltar ao sample inteiro.
- **Zoom da waveform** (botão 1x–16x, segure para voltar; a barra no topo rola) para Slicer, Looper, Bow, Arp e edição de região.
- **Cortar (trim)**: menu do slot → "Cortar o sample para início/fim"; os marcadores são reescalados.
- **Loop Player STR**: time-stretch (WSOLA) — o BPM muda o tempo sem mudar a altura, e a transposição muda a altura sem mudar o tempo.
- **Desfazer** (botão undo): sample, reverse, trim, fatias, região e gestos (até 8 passos por slot, com limite de memória).
- **Exportar**: menu Gf → gravar o que está tocando em WAV estéreo (Music/GfDrps); menu do slot → exportar o sample em WAV.
- **Projeto**: agora salva as posições dos efeitos (XY, sync do delay, LP/HP), hold e as novas opções.
- **MIDI clock de entrada**: menu Gf → "Clock MIDI externo": o BPM segue o clock e start/stop controlam o transporte.
- **Gestos**: segure o ● para quantizar (1/16, 1/8, 1/4), deslocar, ou apagar só o pad de efeito / só os toques da waveform.
- **Ajuda**: o "?" mostra um overlay com cada controle numerado sobre a própria tela (segure o "?" para o texto completo).
- Botões que não valem para o modo atual ficam esmaecidos (grade, legato, editar, ação do modo).

## 1.3 (build 4)
- **Estabilidade**: corrigido o fechamento ao mudar o número de fatias no modo Arp (índice fora do limite ao desenhar) e uma exceção no Looper ao soltar os dedos depois do Stop; a thread de áudio não derruba mais o app (silêncio no bloco com erro); importação de arquivos grandes por streaming (codec), limite de 4 min, `largeHeap` e aviso se faltar memória.
- **Latência**: buffer de áudio do menor tamanho estável (2 bursts) que cresce sozinho se houver underrun; toques sem agrupamento (`requestUnbufferedDispatch`) na waveform e no pad.
- **Scratch**: interpolação cúbica, o alvo do dedo continua se movendo entre os eventos de toque (sem degraus de 60 Hz), mola mais suave e gate de volume mais rápido para abrir e mais lento para fechar. Sem estalos nem modulação de 60 Hz (testado).
- **Latch** (botão verde): o som continua ao soltar o dedo e ao trocar de modo; um novo gesto ou desligar o latch solta. Vale também para o padrão do Arp, o Looper e o auto-play.
- **Slicer**: botão ▶ (auto-play das fatias da esquerda para a direita na grade do BPM), 🎲 shuffle, **Q** quantiza os toques na grade (segure o Q para 1/4–1/32). O Q também vale no Keyboard.
- **Loop Player**: o primeiro toque já liga o loop (e o transporte), sem precisar apertar play.
- **Delay**: 10 ms a 4000 ms, em milissegundos inteiros (1 em 1 ms nas faixas curtas) — perto de 10 ms vira filtro pente/eco metálico; acima de ~150 ms o feedback é ping-pong. Botão **sync** trava o tempo no BPM (1/32 a 1 compasso, retas e pontuadas). O pad mostra o tempo em ms ou a nota.
- **BPM**: arrastar na horizontal (direita = mais rápido), o topo da tela é a borda.
- **Gestos**: a gravação começa no primeiro toque (waveform ou pad de efeito), em qualquer modo; os movimentos do pad de efeito (inclusive master) são gravados e repetidos; os gestos são salvos e carregados com o projeto.

## 1.2 (build 3)
- **ADSR completo em todos os modos**: novos sliders Decay e Sustain; Attack/Decay/Sustain/Release valem para Slicer, Looper, Bow, Keyboard, Loop Player e Arpejador, e agem ao vivo em notas seguradas. Tape: o release controla o tempo da parada de fita.
- **Reverb "cloud"** reescrito (plate de Dattorro): cauda longa, densa e difusa, com modulação lenta nas linhas de atraso e amortecimento dos agudos — sem ressonâncias metálicas (testado por métricas de periodicidade e picos espectrais).
- **Arpejador refeito**: usa as fatias sob os dedos (grade 0/4/8/16/32 ou fatias manuais), direção (UP, DN, U-D, RND, ORD) no botão rosa, taxa 1/4–1/32 no botão amarelo, notas travadas na grade do BPM, fatia tocada acende na tela, usa o ADSR do slot.
- Filtro: um único módulo (toque de novo no ícone alterna LP/HP).
- Versão do app aparece no botão "Gf vX.Y" e em Gf → Sobre.
- Projetos salvam decay, sustain, direção e taxa do arpejador.

## 1.1
- Botão ■ realmente para tudo; filtro único LP/HP; fatias manuais e automáticas; reverse; reverb FDN; sem estalos.
