# Giro 360

App Android de gravação de vídeo para plataformas giratórias 360, com câmera lenta
(slow motion) e molduras personalizadas sobrepostas.

## O que já funciona neste projeto

- **Gravação de vídeo** com a câmera traseira (CameraX 1.6.0), salvando direto na
  galeria em `Movies/Giro360`.
- **Câmera lenta (slow motion):** botão "Câmera lenta" que pede alta qualidade/taxa de
  quadros. Se o aparelho **não suportar** alta taxa, o app avisa e volta sozinho para a
  velocidade padrão (sem quebrar).
- **Molduras personalizadas:** botão de adicionar moldura abre a galeria; você escolhe
  qualquer PNG (de preferência com fundo transparente) e ele fica sobreposto à câmera.
  Pode remover ou trocar a qualquer momento. É o "upload" que você pediu — o usuário
  coloca a própria moldura, nada fica embutido no código.

## Como abrir e rodar

1. Instale o **Android Studio** (developer.android.com/studio).
2. **File → Open** e selecione esta pasta `Giro360`.
3. Aguarde o Gradle sincronizar (baixa as dependências na primeira vez; demora).
4. Conecte o celular com **Depuração USB** ligada e clique no ▶ (Run).

Para gerar o **APK** (instalar sem cabo / passar para outras pessoas):
**Build → Build Bundle(s)/APK(s) → Build APK(s)**.

## O que ainda NÃO está pronto (próximos passos)

- **Boomerang (vai e volta):** não está implementado neste código. Boomerang exige
  pós-processamento do vídeo gravado (pegar o clipe, duplicar invertido e juntar), o que
  precisa de uma biblioteca de edição de vídeo. Recomendo fazer numa segunda etapa.
- **"Queimar" a moldura dentro do vídeo exportado:** atualmente a moldura aparece na
  tela durante a gravação (preview), mas o arquivo MP4 salvo é só a câmera — a moldura
  **não** fica gravada dentro do vídeo ainda. Isso também precisa de uma etapa de
  composição/pós-processamento de vídeo.

Esses dois pontos compartilham a mesma necessidade técnica (processar o vídeo depois de
gravado), então faz sentido implementar os dois juntos numa próxima rodada.
