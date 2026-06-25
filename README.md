# 🤖 GeraldinhoGaucho: Robô Autônomo para Robocode 1v1

**Autores:** Caio Cordeiro Matos, Gabriel Marcone Magalhães Santos, João Guilherme Souza dos Santos  
**Contexto:** Competição Acadêmica (Combate 1v1)  
**Linguagem:** Java  

---

## 📖 Sobre o Projeto
O **GeraldinhoGaucho** é um agente autônomo da categoria *AdvancedRobot* desenvolvido para competições táticas de alto nível na plataforma **Robocode**. Projetado especificamente para cenários de combate um contra um (1v1), o robô opera como um sistema adaptativo em tempo real. Longe de utilizar comportamentos estáticos ou baseados em regras fixas, ele integra algoritmos estatísticos avançados para mapear, aprender e explorar os padrões comportamentais, as falhas de mira e as tendências de movimentação dos adversários ao longo dos *rounds*.

---

## 🎯 Objetivos do Projeto
✅ Implementar movimentação evasiva avançada através de **Wave Surfing** binarizado.  
✅ Desenvolver um sistema de mira preditiva de alta precisão baseado em **GuessFactor Targeting**.  
✅ Utilizar **Segmentação Estatística Multidimensional** para isolar dados por distância, velocidade e aceleração.  
✅ Aplicar técnicas de **Wall Smoothing** (suavização de parede) para evitar colisões e perdas de aceleração nas bordas.  
✅ Consolidar conceitos práticos de **Programação Orientada a Objetos (POO)** e **Estruturas de Dados** dinâmicas aplicadas à Inteligência Artificial.

---

## ⚔️ Estratégias e Componentes Principais

### 1. Movimentação Evasiva (Wave Surfing)
O robô intercepta e rastreia ondas de energia geradas a cada disparo detectado no radar inimigo (*EnemyWave*). O campo é segmentado em uma matriz estatística que correlaciona a **Distância** e a **Velocidade Lateral** atual do robô em 47 *bins* (divisões angulares de impacto). Antes de tomar uma decisão de movimento, o robô projeta sua posição física futura frame a frame e escolhe o vetor que apresenta o menor risco acumulado de acerto histórico.

### 2. Mira Preditiva (GuessFactor Targeting)
O canhão do GeraldinhoGaucho mapeia o ângulo máximo de escape do oponente através do disparo constante de ondas virtuais de monitoramento (*GunWaves*). Ao registrar o fator de desvio (*GuessFactor*) exato utilizado pelo inimigo para esquivar de disparos anteriores, o sistema reconstrói um perfil estatístico que aponta com alta precisão onde o alvo estará quando o projétil físico cruzar a arena.

### 3. Segmentação Dinâmica e Gerenciamento de Energia
As estatísticas de mira e movimento são isoladas em tensores multidimensionais detalhados, permitindo ao robô detectar se o oponente altera seu padrão de esquiva sob condições específicas (como aceleração brusca ou proximidade das paredes). O gerenciamento de energia ajusta a potência dos projéteis dinamicamente, priorizando disparos de força máxima (`3.0`) apenas em distâncias curtas para otimizar o dano e poupar energia em confrontos de longo alcance.

---

## 🛠️ Componentes e Tecnologias Utilizadas
- **Robocode API**: Utilização de controle assíncrono de hardware (`setAhead`, `setTurnGunRightRadians`, `setTurnRadarRight`).
- **Geometria Computacional Avançada**: Uso intensivo das classes `Point2D.Double` e `Rectangle2D` para modelagem vetorial e previsão de trajetórias.
- **Previsão Precisa de Física (Precise Prediction)**: Clonagem dos coeficientes nativos de aceleração (`Rules.ACCELERATION`) e taxas de curva do motor do jogo para simulações puras de posicionamento.
- **Arquivos Dinâmicos e Listas Flexíveis**: Estruturas do tipo `ArrayList` encapsuladas para a gerência concorrente de ondas ativas no mapa.

---

## ⚙️ Arquitetura de Execução (Fluxo)
O ciclo básico de processamento do robô ocorre de forma contínua a cada iteração do loop principal:

```text
Radar detecta o inimigo 
       ↓
Coleta e armazena dados físicos (Energia, Posição, Vetores)
       ↓
Processa ondas inimigas e calcula riscos de impacto
       ↓
Executa evasão matemática e suavização de trajetórias junto às paredes
       ↓
Atualiza matrizes de aprendizado estatístico (Normal e Fast Segments)
       ↓
Calcula a solução ideal de mira por GuessFactor
       ↓
Efetua o disparo com potência otimizada