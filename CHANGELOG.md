# Changelog

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
