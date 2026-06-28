# Como gerar o APK na nuvem (GitHub Actions)

Você não vai instalar nada no PC. O GitHub compila o app e te dá o APK pra baixar.
Siga na ordem.

## Passo 1 — Criar conta no GitHub (se ainda não tiver)
Acesse github.com e crie uma conta gratuita.

## Passo 2 — Criar um repositório
1. Clique no **+** no canto superior direito → **New repository**.
2. Dê um nome (ex.: `giro360`).
3. Pode deixar como **Private** (só você vê) ou Public, tanto faz.
4. **NÃO** marque nenhuma opção de "Add README/gitignore".
5. Clique em **Create repository**.

## Passo 3 — Subir os arquivos do projeto
A forma mais fácil, sem usar comandos:

1. Na página do repositório recém-criado, clique em **uploading an existing file**
   (link no meio da tela).
2. **Descompacte o `Giro360.zip`** no seu PC primeiro.
3. Arraste **todo o conteúdo de dentro da pasta `Giro360`** (não a pasta em si — os
   arquivos e subpastas que estão dentro dela) para a área de upload do GitHub.
   - Importante: a pasta `.github` precisa subir junto. Se ela não aparecer ao arrastar,
     pode ser que seu sistema esteja escondendo pastas que começam com ponto. Veja a
     observação no fim deste guia.
4. Espere o upload terminar e clique em **Commit changes**.

## Passo 4 — O build começa sozinho
Assim que os arquivos sobem, o GitHub já começa a compilar.

1. Clique na aba **Actions** (no topo do repositório).
2. Você verá um item "Build APK" rodando (bolinha amarela girando).
3. Espere terminar — leva uns **3 a 6 minutos** na primeira vez. Vira um ✅ verde
   quando dá certo.

## Passo 5 — Baixar o APK
1. Clique no build que terminou (o item com ✅).
2. Role até o fim da página, seção **Artifacts**.
3. Clique em **Giro360-apk** para baixar um arquivo .zip.
4. Descompacte → dentro está o **app-debug.apk**.

## Passo 6 — Instalar no celular
1. Passe o `app-debug.apk` para o celular (cabo USB, Google Drive, WhatsApp web etc.).
2. Abra o arquivo no celular.
3. O Android vai pedir para permitir "instalar de fonte desconhecida" — autorize.
4. Pronto, o app instala.

---

## Se o build falhar (✅ virar ❌)
Não tem problema, acontece. Clique no build vermelho, depois no passo que falhou
("Compilar o APK de debug") para ver a mensagem de erro. Copie o trecho do erro e me
mande — eu corrijo e te passo o ajuste. É assim que se resolve, por iteração.

## Observação sobre a pasta `.github`
Essa pasta é o que dá a instrução de build pro GitHub. Em alguns sistemas ela fica
oculta porque começa com ponto. Se ao arrastar os arquivos ela não for junto:
- **Windows:** no Explorador de Arquivos, aba "Exibir" → marque "Itens ocultos".
- **Mac:** aperte Cmd + Shift + . (ponto) para mostrar arquivos ocultos.

Sem essa pasta, o build não dispara — então confira que ela subiu (deve aparecer na
lista de arquivos do repositório).
