import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.geom.*;
import java.util.ArrayList;

public class GeraldinhoGaucho extends AdvancedRobot {
	// ========== Wave Surfing ==========
	public static int BINS = 47;
    // -----------------------------------------------------------------------
    // SEGMENTAÇÃO MULTIDIMENSIONAL
    //   Eixo 0 – Distância até o inimigo  : 0=curta  1=média  2=longa
    //   Eixo 1 – Velocidade lateral própria: 0=baixa  1=média  2=alta
    //   Eixo 2 – Tempo desde última reversão: 0=recente 1=intermediário 2=longo
    //   Total de segmentos: 4 × 4 × 3 = 48  ->  48 × 47 bins ≈ 2 256 doubles ≈ 18 KB
    // -----------------------------------------------------------------------
    public static final int SEG_DIST  = 4;  // segmentos de distância
    public static final int SEG_VEL   = 4;  // segmentos de velocidade lateral
    public static final int SEG_TIME  = 3;  // segmentos de tempo de reversão
 
    // Array 4-D: [dist][vel][timeSinceReverse][bins]
    public static double[][][][] surfStats = new double[SEG_DIST][SEG_VEL][SEG_TIME][BINS];
 
    // Rastreamento de reversão de direção
    private int   lastDirection    = 1;
    private long  lastReverseTime  = 0;

    //public static double surfStats[] = new double[BINS];
    private ArrayList<EnemyWave> enemyWaves;
    private ArrayList<Integer> surfDirections;
    private ArrayList<Double> surfAbsBearings;
    static final double A_LITTLE_LESS_THAN_HALF_PI = 1.25;

    // ========== Localizacao ==========
	private Point2D.Double myLocation;
	private Point2D.Double enemyLocation;
    public static double opponentEnergy = 100.0;

	// ========== Campo de Batalha ==========
	private Rectangle2D battleField;
    private final double WALL_MARGIN = 36;
    public static double WALL_STICK = 160;

    // ========== Metricas de Desempenho ==========
    private int shotsFired = 0; // tiros que o robo disparou
    private int shotsHit = 0;  // tiros que acertaram o inimigo
    private int shotsReceived = 0; // tiros que o inimigo acertou em nós
    private double energySum = 0; // soma de energia para calcular media
    private int energySamples = 0; // quantidade de amostras coletadas
    private int wins = 0; // vitórias acumuladas
    private int deaths = 0; // derrotas acumuladas

    //Variaveis para controle de tiro
    private double finalBulletPower;
    private double finalGunTurn;
    private static final int MIDDLE_GUN_FACTOR = 16;
    private static final int TOTAL_GUN_FACTORS = 33;

    private static double[][][][][][][] normalGunSegmentation = new double[3][5][5][5][10][3][TOTAL_GUN_FACTORS];
    private static double[][][][][][][] fastGunSegmentation = new double[3][5][5][5][8][3][TOTAL_GUN_FACTORS];

    public ArrayList<GunWave> gunWaves;

    private double enemyVelocity;
    private double lastEnemyVelocity;
    private double enemyLateralVelocity;
    private double lastVelocityChangeTime;
    private double moveDirection = 1;
    private double escapeEnvelope = moveDirection * maxEscapeAngle(Rules.getBulletSpeed(3));
    private double wallDistance;
    private double reverseWallDistance;

	/* ***************************************************************
	* Metodo: run
	* Funcao: Metodo principal do robo, responsavel por executar a logica de movimento e combate.
	* Parametros: Sem parametros.
	* Retorno: void
	*************************************************************** */
	public void run() {
		// Configuracoes de cor do robo (copa huehuehuebr)
		setBodyColor(Color.GREEN);
		setGunColor(Color.YELLOW);
		setRadarColor(Color.BLUE);
		
		// Permite que a base, a arma e o radar do robo girem de forma independente
		setAdjustGunForRobotTurn(true); 
        setAdjustRadarForGunTurn(true);

		// Configura a area onde o robo pode se mover, considerando uma margem para evitar colisoes com as paredes
		battleField = new Rectangle2D.Double(18, 18, getBattleFieldWidth() - WALL_MARGIN, getBattleFieldHeight() - WALL_MARGIN);
		
		// Inicializa as variaveis para a tecnica de Wave Surfing
		surfDirections = new ArrayList<>();
		surfAbsBearings = new ArrayList<>();
		enemyWaves = new ArrayList<>();
        gunWaves = new ArrayList<>();

		// Laco principal do robo, onde o radar gira continuamente para escanear o ambiente
		do {
            turnRadarRightRadians(1);
        } while (true);
	}

	/* ***************************************************************
	* Metodo: updateWaves
	* Funcao: Metodo responsavel por atualizar as ondas de inimigo, removendo aquelas que ja passaram do robo.
	* Parametros: Sem parametros.
	* Retorno: void
	*************************************************************** */
	public void updateWaves() {
		for (int i = 0; i < enemyWaves.size(); i++) {
			EnemyWave ew = (EnemyWave)enemyWaves.get(i);
            double timeVariation = getTime() - ew.fireTime;
			ew.distanceTraveled = timeVariation * ew.bulletVelocity;
			if (ew.distanceTraveled >
				myLocation.distance(ew.fireLocation) + 50) {
				enemyWaves.remove(i);
				i--;
			}
		}
    }

	/* ***************************************************************
	* Metodo: doSurfing
	* Funcao: Metodo responsavel por executar a logica de movimentacao baseada na tecnica de Wave Surfing,
      escolhendo a melhor direcao para evitar os tiros inimigos.
	* Parametros: Sem parametros.
	* Retorno: void
	*************************************************************** */
	public void doSurfing() {
        EnemyWave surfWave = getClosestSurfableWave();

        if (surfWave == null) {
            return;
        }

        double dangerLeft = checkDanger(surfWave, -1);
        double dangerRight = checkDanger(surfWave, 1);

        double goAngle = absBearing(surfWave.fireLocation, myLocation);
        if (dangerLeft < dangerRight) {
            goAngle = wallSmoothing(myLocation, goAngle - (A_LITTLE_LESS_THAN_HALF_PI), -1);
        } else {
            goAngle = wallSmoothing(myLocation, goAngle + (A_LITTLE_LESS_THAN_HALF_PI), 1);
        }

        setBackAsFront(this, goAngle);
    }

	/* ***************************************************************
	* Metodo: getClosestSurfableWave
	* Funcao: Metodo responsavel por encontrar a onda de inimigo mais proxima que ainda pode ser evitada pelo robo.
	* Parametros: Sem parametros.
	* Retorno: EnemyWave - a onda de inimigo mais proxima que ainda pode ser evitada.
	*************************************************************** */
	public EnemyWave getClosestSurfableWave() {
        double closestDistance = Double.POSITIVE_INFINITY;
        EnemyWave surfWave = null;

        for (int i = 0; i < enemyWaves.size(); i++) {
            EnemyWave ew = (EnemyWave)enemyWaves.get(i);
            double distance = myLocation.distance(ew.fireLocation) - ew.distanceTraveled;

            if (distance > ew.bulletVelocity && distance < closestDistance) {
                surfWave = ew;
                closestDistance = distance;
            }
        }

        return surfWave;
    }

	/* ***************************************************************
	* Metodo: checkDanger
	* Funcao: Metodo responsavel por calcular o nivel de perigo de uma determinada direcao em relacao a uma onda
      de inimigo, utilizando as estatisticas de surf.
	* Parametros: EnemyWave surfWave - a onda de inimigo para a qual o perigo sera calculado; int direction - a 
      direcao a ser avaliada (-1 para esquerda, 1 para direita).
	* Retorno: double - o nivel de perigo da direcao avaliada, onde valores mais altos indicam maior perigo.
	*************************************************************** */
	public double checkDanger(EnemyWave surfWave, int direction) {
        Point2D.Double predictedPosition = predictPosition(surfWave, direction);
        int centralIndex = getFactorIndex(surfWave, predictedPosition);

        double lastPredictedDistance = surfWave.fireLocation.distance(predictedPosition);
        double smoothedDanger = 0;

        // Janela deslizante de index-2 até index+2
        for (int i = -2; i <= 2; i++) {
            int currentIndex = centralIndex + i;
            
            // Prevenção de OutOfBounds (limites do array)
            if (currentIndex >= 0 && currentIndex < BINS) {
                double weight;
                
                // Distribuição de pesos que você sugeriu
                if (Math.abs(i) == 0) weight = 1.00;
                else if (Math.abs(i) == 1) weight = 0.50;
                else weight = 0.25;

                // Le o valor cru da matriz multidimensional e multiplicamos pelo peso
                double rawDanger = surfStats[surfWave.segDist][surfWave.segVel][surfWave.segTime][currentIndex];
                smoothedDanger += (rawDanger * weight);
            }
        }

        // Mantemos a fórmula original que soma o perigo lido e penaliza rotas próximas da onda
        return (smoothedDanger + 0.01 / (Math.abs(centralIndex - (BINS/2)) + 1)) / Math.pow(lastPredictedDistance, 4);
    }

    /* ***************************************************************
    * Metodo: getFactorIndex
    * Funcao: Metodo responsavel por calcular o indice do array de estatisticas de surf com base na posicao prevista do robo
      em relacao a uma onda de inimigo, utilizando o angulo de escape e a direcao da onda.
    * Parametros: EnemyWave ew - a onda de inimigo para a qual o indice sera calculado; Point2D.Double targetLocation - a
      posicao prevista do robo.
    * Retorno: int - o indice do array de estatisticas de surf correspondente a posicao prevista do robo em relacao a onda de inimigo.
    *************************************************************** */
	public static int getFactorIndex(EnemyWave ew, Point2D.Double targetLocation) {
        double offsetAngle = (absBearing(ew.fireLocation, targetLocation) - ew.directAngle);
        double factor = Utils.normalRelativeAngle(offsetAngle) / maxEscapeAngle(ew.bulletVelocity) * ew.direction;

        return (int)limit(0,(factor * (BINS/2) + (BINS/2)), BINS - 1);
    }

    /* ***************************************************************
    * Metodo: predictPosition
    * Funcao: Metodo responsavel por prever a posicao futura do robo com base na direcao de movimentacao escolhida e na onda de inimigo,
      simulando o movimento do robo para avaliar o perigo das direcoes.
    * Parametros: EnemyWave surfWave - a onda de inimigo para a qual a posicao sera prevista; int direction - a direcao de movimentacao escolhida
      (-1 para esquerda, 1 para direita).
    * Retorno: Point2D.Double - a posicao prevista do robo no futuro com base na direcao de movimentacao escolhida e na onda de inimigo.
    * Obs: Este metodo simula o movimento do robo em ticks futuros, considerando a velocidade, a direcao e as limitacoes de movimento, para prever
      onde o robo estaria em relacao a onda de inimigo. O loop continua ate que a posicao prevista esteja dentro do alcance da onda de inimigo ou ate
      que um limite de ticks seja atingido, para evitar loops infinitos. O resultado desta previsao e utilizado para calcular o indice de perigo da
      direcao escolhida, ajudando o robo a escolher a melhor direcao para evitar os tiros inimigos.
    *************************************************************** */
	public Point2D.Double predictPosition(EnemyWave surfWave, int direction) {
        Point2D.Double predictedPosition = (Point2D.Double)myLocation.clone();
        double predictedVelocity = getVelocity();
        double predictedHeading = getHeadingRadians();
        double maxTurning, moveAngle, moveDir;

        int counter = 0; // Contador de ticks simulados
        boolean intercepted = false;

        do {
            moveAngle = wallSmoothing(predictedPosition, absBearing(surfWave.fireLocation, predictedPosition) + (direction * (A_LITTLE_LESS_THAN_HALF_PI)), direction) - predictedHeading;
            moveDir = 1;

            if(Math.cos(moveAngle) < 0) {
                moveAngle += Math.PI;
                moveDir = -1;
            }

            moveAngle = Utils.normalRelativeAngle(moveAngle);

            maxTurning = Math.PI/720d*(40d - 3d*Math.abs(predictedVelocity));
            predictedHeading = Utils.normalRelativeAngle(predictedHeading + limit(-maxTurning, moveAngle, maxTurning));

            predictedVelocity += (predictedVelocity * moveDir < 0 ? 2 * moveDir : moveDir);
            predictedVelocity = limit(-8, predictedVelocity, 8);

            predictedPosition = project(predictedPosition, predictedHeading, predictedVelocity);

            counter++;

            if (predictedPosition.distance(surfWave.fireLocation) < surfWave.distanceTraveled + (counter * surfWave.bulletVelocity) + surfWave.bulletVelocity) {
                intercepted = true;
            }
        } while(!intercepted && counter < 500);

        return predictedPosition;
    }

	/* ***************************************************************
	* Metodo: onScannedRobot
	* Funcao: Metodo chamado quando o robo escaneia outro robo. Responsavel por calcular a direcao do inimigo e atirar.
	* Parametros: ScannedRobotEvent e - evento que contem informacoes sobre o robo escaneado.
	* Retorno: void
	*************************************************************** */
	public void onScannedRobot(ScannedRobotEvent e) {
		// Utilizando a tecnica de Wave Surfing como movimentacao
		myLocation = new Point2D.Double(getX(), getY());

        energySum += getEnergy();
        energySamples++;

        double lateralVelocity = getVelocity()*Math.sin(e.getBearingRadians());
        double absBearing = e.getBearingRadians() + getHeadingRadians();

        // Rastrear reversão de direção
        int currentDir = (lateralVelocity >= 0) ? 1 : -1;
        if (currentDir != lastDirection) {
            lastDirection = currentDir;
            lastReverseTime = getTime();
        }

        surfDirections.add(0, new Integer(currentDir));
        surfAbsBearings.add(0, new Double(absBearing + Math.PI));


        double bulletPower = opponentEnergy - e.getEnergy();

        if (bulletPower < 3.01 && bulletPower > 0.09 && surfDirections.size() > 2 && enemyLocation != null) {
            EnemyWave ew = new EnemyWave();
            ew.fireTime = getTime() - 1;
            ew.bulletVelocity = bulletVelocity(bulletPower);
            ew.distanceTraveled = bulletVelocity(bulletPower);
            ew.direction = ((Integer)surfDirections.get(2)).intValue();
            ew.directAngle = ((Double)surfAbsBearings.get(2)).doubleValue();
            ew.fireLocation = (Point2D.Double)enemyLocation.clone(); // last tick

            // ---- SEGMENTAÇÃO no momento do disparo ----
            double dist = myLocation.distance(enemyLocation);
            double absLat = Math.abs(lateralVelocity);
            long timeSinceReverse = getTime() - lastReverseTime;
 
            ew.segDist = getDistSegment(dist);
            ew.segVel = getVelSegment(absLat);
            ew.segTime = getTimeSegment(timeSinceReverse);
 
            // A wave carrega uma referência direta ao slice [segDist][segVel][segTime]
            // ew.stats = surfStats[segDist][segVel][segTime];
            // -------------------------------------------

            enemyWaves.add(ew);
        }

        opponentEnergy = e.getEnergy();

        enemyLocation = project(myLocation, absBearing, e.getDistance());

        updateWaves();
        doSurfing();

        // gun code would go here...
        finalBulletPower = 1.9;
        //Estrategia de aumentar o poder de fogo caso o inimigo esteja perto
        if (e.getDistance() < 240) {
            finalBulletPower = 3.0;
        }
        //Estrategia de calcular o poder de fogo pela energia do inimigo e propria energia
        finalBulletPower = Math.min(finalBulletPower, e.getEnergy()/4);
        finalBulletPower = Math.min(finalBulletPower, getEnergy()/2);
        double bulletSpeed = Rules.getBulletSpeed(finalBulletPower);

        lastEnemyVelocity = enemyVelocity;
        enemyVelocity = e.getVelocity();
        absBearing = e.getBearingRadians() + getHeadingRadians();
        myLocation = new Point2D.Double(getX(), getY());
        double enemyDistance = e.getDistance();

        //Encontra a velocidade lateral e direcao do inimigo para determinar o escape envelope
        enemyLateralVelocity = enemyVelocity * Math.sin(e.getHeadingRadians() - absBearing);
        if (enemyLateralVelocity != 0)
            moveDirection = (enemyLateralVelocity > 0 ? 1 : -1);
        escapeEnvelope = moveDirection * maxEscapeAngle(bulletSpeed);

        //Retorna a distancia lateral do inimigo ate a parede como um valor entre 0 e 1
        wallDistance = 1.1;

        while(wallDistance >= 0.1){
            wallDistance -= 0.1;
            Point2D.Double predictedBulletPosition = project(myLocation,absBearing + wallDistance * escapeEnvelope, enemyDistance);
            if(battleField.contains(predictedBulletPosition))
                break;
        }

        //Faz o mesmo calculo para o outro lado
        reverseWallDistance = 1.1;
        while(reverseWallDistance >= 0.1){
            reverseWallDistance -= 0.1;
            Point2D.Double predictedBulletPosition = project(myLocation,absBearing - reverseWallDistance * escapeEnvelope, enemyDistance);
            if(battleField.contains(predictedBulletPosition))
                break;
        }

        //Fator de alteracao de velocidade de movimento do inimigo para detectar robos imprevisiveis
        double moveTime = bulletSpeed * lastVelocityChangeTime++ / enemyDistance;

        //Segmentacao
        if (e.getEnergy() > 0 && getEnergy() > 0) {
            //Segmentacao pela distancia ate o inimigo
            int distanceIndex = (int) enemyDistance / 240;
            int fastDistanceIndex = (int) enemyDistance / 360;

            //Segmentacao pela distancia do inimigo ate a parede
            int nearWallIndex = (int) (wallDistance * 3);
            int fastNearWallIndex = (int) (wallDistance * 1.5);

            //Segmentacao pela distancia do inimigo ate a parede inversa
            int reverseNearWallIndex = (int) (reverseWallDistance * 2);
            int fastReverseNearWallIndex = (int) (reverseWallDistance * 1.25);

            //Segmentacao pela velocidade lateral do inimigo
            int lateralVelocityIndex = (int) Math.abs(enemyLateralVelocity / 2);
            int fastLateralVelocityIndex = (int) Math.abs(enemyLateralVelocity / 2.67);

            //Segmentacao pelo tempo desde a ultima mudanca de velocidade do inimigo
            int moveTimeIndex = moveTime < .4 ? 1 : moveTime < .8 ? 2 : moveTime < 1.2 ? 3 : 4;
            int fastMoveTimeIndex = moveTime < .6 ? 1 : 2;

            //Segmentacao pela aceleracao do inimigo
            int accelerationIndex = (int) Math.round(Math.abs(enemyVelocity) - Math.abs(lastEnemyVelocity));
            if (accelerationIndex != 0){
                accelerationIndex = accelerationIndex > 0 ? 2 : 1;
            }
            if (accelerationIndex > 0) {
                lastVelocityChangeTime = 0;
                moveTimeIndex = fastMoveTimeIndex = 0;
            }

            //Determina uma nova onda de tiro
            GunWave g = new GunWave();
            g.bulletOrigin = myLocation;
            g.enemyOrigin = g.lastEnemyPosition = project(enemyLocation, e.getHeadingRadians(), -enemyVelocity);
            g.bulletAngle = absBearing(g.bulletOrigin, g.enemyOrigin);
            g.bulletVelocity = bulletSpeed;
            g.fireTime = g.lastTime = getTime() - 1;
            g.escapeEnvelope = escapeEnvelope;
            g.normalSegment = normalGunSegmentation[accelerationIndex][lateralVelocityIndex][moveTimeIndex][nearWallIndex][distanceIndex][reverseNearWallIndex];
            g.fastSegment = fastGunSegmentation[accelerationIndex][fastLateralVelocityIndex][fastMoveTimeIndex][fastNearWallIndex][fastDistanceIndex][fastReverseNearWallIndex];
            gunWaves.add(g);
            //Se o cooldown da arma for 0 significa que esta apta a dar um tiro real que sera utilizado para ponderar os dados
            if (getGunHeat() == 0){
                g.real = true;
            }


            //Percorre as ondas restantes atualizando a posicao dessas no tempo atual
            for (int i = 0; i < gunWaves.size(); i++) {
                GunWave gw = gunWaves.get(i);
                if(gw.update(getTime(), enemyLocation)){
                    gunWaves.remove(gw);
                    i--;
                }
            }

            int bestIndex = MIDDLE_GUN_FACTOR;
            //Procura pelos melhores indexes dada as segmentacoes
            double bestWeightedValueFromNormalSegment = weightedHitsSmoothing(bestIndex, g.normalSegment);
            double bestWeightedValueFromFastSegment = weightedHitsSmoothing(bestIndex, g.fastSegment);
            for (int i = MIDDLE_GUN_FACTOR * 2 - 1; i >= 1; i--) {
                double currentNormalWeightedValue = weightedHitsSmoothing(i, g.normalSegment);
                double currentFastWeightedValue = weightedHitsSmoothing(i, g.fastSegment);
                if (currentNormalWeightedValue + currentFastWeightedValue > bestWeightedValueFromNormalSegment + bestWeightedValueFromFastSegment) {
                    bestIndex = i;
                    bestWeightedValueFromNormalSegment = currentNormalWeightedValue;
                    bestWeightedValueFromFastSegment = currentFastWeightedValue;
                }
            }

            //Ajusta o angulo final de mira da arma de acordo com a possibilidade de atirar realmente ou nao
            if (getGunHeat() < getGunCoolingRate() * 3)
                finalGunTurn = Utils.normalRelativeAngle(absBearing - getGunHeadingRadians() + escapeEnvelope * (bestIndex/(double) MIDDLE_GUN_FACTOR - 1));
            else
                finalGunTurn = Utils.normalRelativeAngle(absBearing-getGunHeadingRadians());

            //Gira a arma e atira
            setTurnGunRightRadians(finalGunTurn);
            if (finalBulletPower > 0){
                setFire(finalBulletPower);
            }

            setTurnRadarRightRadians(Math.tan(e.getBearingRadians() + getHeadingRadians() - getRadarHeadingRadians()) * 2);
        }
	}

    /* ***************************************************************
    * Metodo: logHit
    * Funcao: Metodo responsavel por registrar um hit recebido em relacao a uma onda de inimigo, atualizando as estatisticas
      de surf para melhorar a escolha de direcao em futuras ondas.
    * Parametros: EnemyWave ew - a onda de inimigo que causou o hit; Point2D.Double targetLocation - a posicao onde o robo foi atingido.
    * Retorno: void
    * Obs: Este metodo calcula o indice de perigo correspondente a posicao onde o robo foi atingido em relacao a onda de inimigo, e atualiza as estatisticas
      de surf para que direcoes próximas a essa posicao sejam consideradas mais perigosas no futuro, ajudando o robo a evitar essas direcoes em ondas futuras.
    *************************************************************** */
	public void logHit(EnemyWave ew, Point2D.Double targetLocation) {
       int hitIndex = getFactorIndex(ew, targetLocation);

        // Varremos todos os segmentos possíveis
        for (int d = 0; d < SEG_DIST; d++) {
            for (int v = 0; v < SEG_VEL; v++) {
                for (int t = 0; t < SEG_TIME; t++) {
                    
                    // Calcula a diferença matemática entre o segmento atual do loop e o real da wave
                    double diffDist = Math.abs(ew.segDist - d);
                    double diffVel  = Math.abs(ew.segVel - v);
                    double diffTime = Math.abs(ew.segTime - t);

                    // Cria um peso para o segmento atual baseado na distância matemática, penalizando segmentos mais distantes
                    double segmentWeight = 1.0 / (1.0 + Math.pow(diffDist, 2) + Math.pow(diffVel, 2) + diffTime);

                    // Só processa se o peso for minimamente relevante para poupar CPU
                    if (segmentWeight > 0.05) {
                        for (int x = 0; x < BINS; x++) {
                            // Peso do GuessFactor (onde a bala bateu na linha de esquiva)
                            double binWeight = 1.0 / (Math.pow(hitIndex - x, 2) + 1);

                            // Atualiza a matriz cruzando o peso geográfico com o peso do acerto
                            surfStats[d][v][t][x] += (binWeight * segmentWeight);
                        }
                    }
                }
            }
        }
    }

	/* ***************************************************************
	* Metodo: onHitByBullet
	* Funcao: Metodo chamado quando o robo e atingido por um tiro. Responsavel por reagir ao ataque, como mudar de direcao ou velocidade.
	* Parametros: HitByBulletEvent e - evento que contem informacoes sobre o tiro recebido.
	* Retorno: void
	*************************************************************** */
	public void onHitByBullet(HitByBulletEvent e) {
        if (!enemyWaves.isEmpty()) {
            Point2D.Double hitBulletLocation = new Point2D.Double(e.getBullet().getX(), e.getBullet().getY());
            EnemyWave hitWave = null;

            for (int x = 0; x < enemyWaves.size(); x++) {
                EnemyWave ew = (EnemyWave)enemyWaves.get(x);

                if (Math.abs(ew.distanceTraveled - myLocation.distance(ew.fireLocation)) < 50 && Math.abs(bulletVelocity(e.getBullet().getPower()) - ew.bulletVelocity) < 0.001) {
                    hitWave = ew;
                    break;
                }
            }

            if (hitWave != null) {
                logHit(hitWave, hitBulletLocation);

                enemyWaves.remove(enemyWaves.lastIndexOf(hitWave));
            }
        }
    shotsReceived++;
	}

    /* ***************************************************************
    * Metodo: wallSmoothing
    * Funcao: Metodo responsavel por ajustar o angulo de movimentacao do robo para evitar colisoes com as paredes, utilizando uma tecnica de "wall smoothing".
    * Parametros: Point2D.Double botLocation - a posicao atual do robo; double angle - o angulo de movimentacao desejado; int orientation - a direcao de movimentacao escolhida
      (-1 para esquerda, 1 para direita).
    * Retorno: double - o angulo de movimentacao ajustado para evitar colisoes com as paredes, mantendo o robo dentro do campo de batalha.
    ***************************************************************** */
	public double wallSmoothing(Point2D.Double botLocation, double angle, int orientation) {
        while (!battleField.contains(project(botLocation, angle, WALL_STICK))) {
            angle += orientation*0.05;
        }
        return angle;
    }

    /* ***************************************************************
    * Metodo: project
    * Funcao: Metodo responsavel por calcular uma nova posicao com base em uma posicao de origem, um angulo e uma distancia, utilizado para prever posicoes
      futuras do robo.
    * Parametros: Point2D.Double sourceLocation - a posicao de origem; double angle - o angulo de movimentacao; double length - a distancia a ser projetada.
    * Retorno: Point2D.Double - a nova posicao calculada com base na posicao de origem, angulo e distancia, utilizada para prever posicoes futuras do robo em relacao a ondas de inimigo.
    ***************************************************************** */
    public static Point2D.Double project(Point2D.Double sourceLocation, double angle, double length) {
        return new Point2D.Double(sourceLocation.x + Math.sin(angle) * length, sourceLocation.y + Math.cos(angle) * length);
    }

    //Metodo weightedHitsSmoothing
    //Determina a densidade de acertos de um index de acordo com os outros indexes ponderando pela distancia ate eles
    public static double weightedHitsSmoothing(int guessFactorIndex, double[] currentSegment) {
        double weightedSum = 0;
        for (int i = 1; i < TOTAL_GUN_FACTORS - 1; i++) {
            int distanceToGFIndex = Math.abs(guessFactorIndex - i);
            weightedSum += currentSegment[i] / Math.sqrt(distanceToGFIndex + 1.0);
        }
        return weightedSum;
    }

    //Metodo findGuessFactorIndex
    //Encontra o index referente ao guess factor que atingiu o oponente
    public int findGuessFactorIndex(double startAngle, double newAngle, double escapeEnvelope) {
        double guessFactor = Utils.normalRelativeAngle(newAngle - startAngle) / escapeEnvelope;
        int guessFactorIndex = (int)Math.round((1 + guessFactor) * MIDDLE_GUN_FACTOR);
        return (int)limit(1, guessFactorIndex, TOTAL_GUN_FACTORS - 1);
    }

    /* ***************************************************************
    * Metodo: absBearing
    * Funcao: Metodo responsavel por calcular o angulo absoluto entre duas posicoes, utilizado para determinar a direcao de inimigos e tiros.
    * Parametros: Point2D.Double source - a posicao de origem; Point2D.Double target - a posicao de destino.
    * Retorno: double - o angulo absoluto entre as duas posicoes, utilizado para determinar a direcao de inimigos e tiros.
    ***************************************************************** */
    public static double absBearing(Point2D.Double source, Point2D.Double target) {
        return Math.atan2(target.x - source.x, target.y - source.y);
    }

    /* ***************************************************************
    * Metodo: limit
    * Funcao: Metodo responsavel por limitar um valor dentro de um intervalo especificado, utilizado para garantir que variaveis como
      velocidade e angulos fiquem dentro de limites aceitaveis.
    * Parametros: double min - o valor minimo permitido; double value - o valor a ser limitado; double max - o valor maximo permitido.
    * Retorno: double - o valor limitado dentro do intervalo especificado, garantindo que variaveis como velocidade e angulos
      fiquem dentro de limites aceitaveis.
    ***************************************************************** */
    public static double limit(double min, double value, double max) {
        return Math.max(min, Math.min(value, max));
    }
    /* ***************************************************************
    * Metodo: bulletVelocity
    * Funcao: Metodo responsavel por calcular a velocidade de um tiro com base na potencia do tiro, utilizando a formula de velocidade de bala do Robocode.
    * Parametros: double power - a potencia do tiro, que influencia a velocidade da bala.
    * Retorno: double - a velocidade do tiro calculada com base na potencia, utilizando a formula de velocidade de bala do Robocode, onde
      tiros mais potentes resultam em velocidades mais baixas.
    *************************************************************** */
    public static double bulletVelocity(double power) {
        return (20.0 - (3.0*power));
    }

    /* ***************************************************************
    * Metodo: maxEscapeAngle
    * Funcao: Metodo responsavel por calcular o angulo maximo de escape para um tiro com base na velocidade da bala, utilizando a formula
      de angulo de escape do Robocode.
    * Parametros: double velocity - a velocidade da bala, que influencia o angulo maximo de escape.
    * Retorno: double - o angulo maximo de escape calculado com base na velocidade da bala, utilizando a formula de angulo de escape do Robocode, onde
      balas mais lentas permitem angulos de escape maiores.
    ***************************************************************** */
    public static double maxEscapeAngle(double velocity) {
        return Math.asin(8.0/velocity);
    }

    /* ***************************************************************
    * Metodo: setBackAsFront
    * Funcao: Metodo responsavel por ajustar o movimento do robo para que ele possa se mover para frente ou para trás dependendo da direcao escolhida,
      permitindo que o robo se mova de forma mais fluida e eficiente, evitando a necessidade de virar completamente para mudar de direcao.
    * Parametros: AdvancedRobot robot - o robo; double goAngle - o angulo de movimentacao desejado.
    * Retorno: void
    ***************************************************************** */
    public static void setBackAsFront(AdvancedRobot robot, double goAngle) {
        double angle = Utils.normalRelativeAngle(goAngle - robot.getHeadingRadians());
        if (Math.abs(angle) > (A_LITTLE_LESS_THAN_HALF_PI)) {
            if (angle < 0) {
                robot.setTurnRightRadians(Math.PI + angle);
            } else {
                robot.setTurnLeftRadians(Math.PI - angle);
            }
            robot.setBack(100);
        } else {
            if (angle < 0) {
                robot.setTurnLeftRadians(-1*angle);
           } else {
                robot.setTurnRightRadians(angle);
           }
            robot.setAhead(100);
        }
    }
    /* ***************************************************************
    * Metodo: onBulletHit
    * Funcao: Registra quando um tiro nosso acerta o inimigo.
    * Parametros: BulletHitEvent e - evento de acerto.
    * Retorno: void
    *************************************************************** */
    public void onBulletHit(BulletHitEvent e) {
        shotsHit++;
    }

    /* ***************************************************************
    * Metodo: onWin
    * Funcao: Registra vitoria e imprime estatisticas da rodada.
    * Parametros: WinEvent e - evento de vitoria.
    * Retorno: void
    *************************************************************** */
    public void onWin(WinEvent e) {
        wins++;
        printRoundStats("VITÓRIA");
    }

    /* ***************************************************************
    * Metodo: onDeath
    * Funcao: Registra derrota e imprime estatisticas da rodada.
    * Parametros: DeathEvent e - evento de morte.
    * Retorno: void
    *************************************************************** */
    public void onDeath(DeathEvent e) {
        deaths++;
        printRoundStats("DERROTA");
    }

    /* ***************************************************************
    * Metodo: printRoundStats
    * Funcao: Imprime um resumo das metricas da rodada no console.
    * Parametros: String resultado - "VITÓRIA" ou "DERROTA".
    * Retorno: void
    *************************************************************** */
    private void printRoundStats(String resultado) {
        double accuracy = (shotsFired > 0)
            ? (double) shotsHit / shotsFired * 100.0
            : 0.0;
        double avgEnergy = (energySamples > 0)
            ? energySum / energySamples
            : 0.0;

        out.println("=== RODADA " + getRoundNum() + " [" + resultado + "] ===");
        out.println("  Tiros disparados : " + shotsFired);
        out.println("  Tiros acertados  : " + shotsHit);
        out.println("  Taxa de acerto   : " + String.format("%.1f", accuracy) + "%");
        out.println("  Tiros recebidos  : " + shotsReceived);
        out.println("  Energia media    : " + String.format("%.1f", avgEnergy));
        out.println("  Placar geral     : " + wins + "V / " + deaths + "D");
        out.println("  Acuracia surfing : " + shotsReceived + " hits levados");
    }

	class EnemyWave {
		Point2D.Double fireLocation;
		long fireTime;
		double bulletVelocity, directAngle, distanceTraveled;
		int direction;

        // Referência direta ao slice [dist][vel][time] de surfStats
        // double[] stats;
        // Cordenadas ao invez do array
        int segDist;
        int segVel;
        int segTime;
    }

    //Classe GunWave
    //Representa uma onda de tiro que pode ser virtual ou real do robo
    class GunWave {
        GunWave next;
        Point2D.Double bulletOrigin;
        Point2D.Double enemyOrigin;
        Point2D.Double lastEnemyPosition;
        double bulletAngle;
        double bulletVelocity;
        double escapeEnvelope;
        long fireTime;
        long lastTime;
        double[] normalSegment;
        double[] fastSegment;
        boolean real = false;

        boolean update(long time, Point2D currentEnemyPosition) {
            long deltaTime = time - lastTime;
            double deltaX = (currentEnemyPosition.getX() - lastEnemyPosition.getX()) / deltaTime;
            double deltaY = (currentEnemyPosition.getY() - lastEnemyPosition.getY()) / deltaTime;
            do {
                //Se a distancia percorrida pela bala for maior que a distancia da origem da bala ate a posicao do inimigo
                if (bulletOrigin.distance(lastEnemyPosition) <= bulletVelocity * (lastTime - fireTime)) {
                    //Encontra o guess factor da wave e o index referente a esse guess factor para preencher os dados
                    int index = findGuessFactorIndex(bulletAngle, absBearing(bulletOrigin, lastEnemyPosition), escapeEnvelope);
                    index = (int) limit(1, index, MIDDLE_GUN_FACTOR * 2 - 1);

                    //Atribui um peso maior caso a wave represente um tiro real
                    double weightReal = real ? 5 : 1;

                    //Ajusta os dados ja salvos anteriormente mantendo todos como uma porcentagem
                    for (int i = 1; i < MIDDLE_GUN_FACTOR * 2; i++) {
                        //o index 0 de cada segmentacao representa o numero total de acertos registrados sendo utilizado para ponderar os valores
                        normalSegment[i] *= normalSegment[0] / (normalSegment[0] + weightReal);
                        fastSegment[i] *= fastSegment[0] / (fastSegment[0] + weightReal);
                    }

                    //Atualiza o numero total de acertos adicionando o peso da onda atual
                    normalSegment[0] += weightReal;
                    //Da o devido peso ao index do guess factor da onda
                    normalSegment[index] += (weightReal / normalSegment[0]);
                    //Faz a mesma coisa para a segmentacao rapida
                    fastSegment[0] += weightReal;
                    fastSegment[index] += (weightReal / fastSegment[0]);

                    return true;
                }
                lastTime++;
                lastEnemyPosition.setLocation(lastEnemyPosition.getX() + deltaX, lastEnemyPosition.getY() + deltaY);
            }
            while (lastTime < time);
            return false;
        }
    }

    // ===================================================================
    // MÉTODOS AUXILIARES DE SEGMENTAÇÃO
    // ===================================================================
 
    /* ***************************************************************
    * Metodo: getDistSegment
    * Funcao: Metodo responsavel por segmentar a distancia entre o robo e o inimigo em categorias, atribuindo um indice para cada categoria.
    * Parametros: double distance - a distancia entre o robo e o inimigo.
    * Retorno: int - o indice da categoria correspondente a distancia entre o robo e o inimigo
    ******************************************************* */
    public static int getDistSegment(double distance) {
        if (distance < 150) return 0;
        if (distance < 300) return 1;
        if (distance < 500) return 2;
        return 3;
    }
 
    /* ***************************************************************
    * Metodo: getVelSegment
    * Funcao: Metodo responsavel por segmentar a velocidade lateral do inimigo em categorias, atribuindo um indice para cada categoria.
    * Parametros: double absLateralVelocity - a velocidade lateral absoluta do inimigo.
    * Retorno: int - o indice da categoria correspondente a velocidade lateral do inimigo
    ********************************************************* */
    public static int getVelSegment(double absLateralVelocity) {
        if (absLateralVelocity < 1.5) return 0;
        if (absLateralVelocity < 4.0) return 1;
        if (absLateralVelocity < 6.0) return 2;

        return 3;
    }
    
    /* ***************************************************************
    * Metodo: getTimeSegment
    * Funcao: Metodo responsavel por segmentar o tempo desde a ultima reversao de direcao do inimigo em categorias, atribuindo um indice para cada categoria.
    * Parametros: long timeSinceReverse - o tempo desde a ultima reversao de direcao do inimigo.
    * Retorno: int - o indice da categoria correspondente ao tempo desde a ultima reversao
    ******************************************************* */
    public static int getTimeSegment(long timeSinceReverse) {
        if (timeSinceReverse < 10) return 0;
        if (timeSinceReverse < 30) return 1;
        return 2;
    }
}