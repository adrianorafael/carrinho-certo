# Carrinho Certo

App Android para saber o total da compra **antes de chegar ao caixa**.
Aponte a câmera para a etiqueta da gôndola, o app lê nome e preço (OCR 100% offline, embarcado no APK),
você informa a quantidade e o total vai somando. Ao finalizar, a compra vai para o histórico, com resumo
mensal e por supermercado.

- Sem login, sem internet, sem coleta de dados. Tudo fica no celular (SQLite + fotos em pasta privada).
- OCR: ML Kit Text Recognition (modelo latino embarcado) + leitura por geometria da etiqueta
  (preço = maior número; ignora R$/kg, datas, EAN, preço "de").
- Código de barras: ML Kit Barcode Scanning embarcado.

## Baixar
Veja a aba **Releases**: cada push na `main` gera um APK assinado.

## Compilar localmente
Android Studio (JDK 17) → abrir a pasta → Run. Ou `gradle :app:assembleRelease`.

## Assinatura
`signing/carrinho-certo.jks` mantém a mesma assinatura entre versões, para que atualizações instalem por cima
sem perder o histórico. É um app pessoal (não publicado em loja); se o repositório for público e você quiser
proteger a chave, mova-a para *Secrets* do GitHub.
