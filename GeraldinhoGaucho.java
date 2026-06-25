import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.geom.*;
import java.util.ArrayList;

public class GeraldinhoGaucho extends AdvancedRobot {
	// ========== Wave Surfing ==========
	// Numero de bins para estatisticas de surf
	public static int BINS = 47;
    // -----------------------------------------------------------------------
    // SEGMENTACAO MULTIDIMENSIONAL
    //   Eixo 0 - Distancia ate o inimigo  : 0=curta  1=media  2=longa  3=muito longa
    //   Eixo 1 - Velocidade lateral propria: 0=baixa  1=media  2=alta  3=muito alta  4=extrema
    //   Total de segmentos: 4 x 5 = 20  ->  20 x 47 bins ≈ 940 doubles ≈ 7.5 KB
    // -----------------------------------------------------------------------
    // Segmentos de distancia
    public static final int SEG_DIST  = 4;
    // Segmentos de velocidade lateral
    public static final int SEG_VEL   = 5;

    // Array 3-D: [dist][vel][bins] para armazenar estatisticas de surf
    public static double[][][] surfStats = new double[SEG_DIST][SEG_VEL][BINS];

    // Lista de ondas inimigas ativas
    private ArrayList<EnemyWave> enemyWaves;
    // Lista de direcoes de surf
    private ArrayList<Integer> surfDirections;
    // Lista de angulos absolutos para surf
    private ArrayList<Double> surfAbsBearings;
    // Constante para angulo de desvio
    static final double A_LITTLE_LESS_THAN_HALF_PI = 1.25;

    // ========== Localizacao ==========
    // Posicao atual do robo
	private Point2D.Double myLocation;
	// Posicao atual do inimigo
	private Point2D.Double enemyLocation;
	// Energia do oponente
    public static double opponentEnergy = 100.0;

	// ========== Campo de Batalha ==========
	// Area do campo de batalha
	private Rectangle2D battleField;
	// Margem de seguranca das paredes
    private final double WALL_MARGIN = 36;
    // Distancia para suavizacao de parede
    public static double WALL_STICK = 160;

    // Variaveis para controle de tiro
    // Potencia final do tiro
    private double finalBulletPower;
    // Angulo final de giro do canhao
    private double finalGunTurn;
    // Fator medio do canhao
    private static final int MIDDLE_GUN_FACTOR = 16;
    // Total de fatores do canhao
    private static final int TOTAL_GUN_FACTORS = 33;

    // Segmentacao para tiro normal
    private static double[][][][][][][] normalGunSegmentation = new double[3][5][5][5][10][3][TOTAL_GUN_FACTORS];
    // Segmentacao para tiro rapido
    private static double[][][][][][][] fastGunSegmentation = new double[3][5][5][5][8][3][TOTAL_GUN_FACTORS];

    // Lista de ondas de tiro
    public ArrayList<GunWave> gunWaves;

    // Velocidade do inimigo
    private double enemyVelocity;
    // Ultima velocidade do inimigo
    private double lastEnemyVelocity;
    // Velocidade lateral do inimigo
    private double enemyLateralVelocity;
    // Tempo desde ultima mudanca de velocidade
    private double lastVelocityChangeTime;
    // Direcao de movimento (1 ou -1)
    private double moveDirection = 1;
    // Envelope de escape baseado na velocidade do tiro
    private double escapeEnvelope = moveDirection * maxEscapeAngle(Rules.getBulletSpeed(3));
    // Distancia ate a parede
    private double wallDistance;
    // Distancia reversa ate a parede
    private double reverseWallDistance;

    /* ***********************
     * Metodo: run
     * Funcao: Metodo principal que executa o comportamento do robo
     * Parametros: nenhum
     * Retorno: void
     ********************** */
	public void run() {
		// Define cor do corpo como verde
		setBodyColor(Color.GREEN);
		// Define cor do canhao como amarelo
		setGunColor(Color.YELLOW);
		// Define cor do radar como azul
		setRadarColor(Color.BLUE);
		
		// Ajusta canhao para girar junto com o robo
		setAdjustGunForRobotTurn(true); 
		// Ajusta radar para girar junto com o canhao
        setAdjustRadarForGunTurn(true);

		// Inicializa campo de batalha com margens
		battleField = new Rectangle2D.Double(18, 18, getBattleFieldWidth() - WALL_MARGIN, getBattleFieldHeight() - WALL_MARGIN);
		
		// Inicializa listas de surf
		surfDirections = new ArrayList<>();
		surfAbsBearings = new ArrayList<>();
		// Inicializa lista de ondas inimigas
		enemyWaves = new ArrayList<>();
		// Inicializa lista de ondas de tiro
        gunWaves = new ArrayList<>();

		// Loop principal
		do {
			// Gira o radar continuamente
            turnRadarRightRadians(1);
        } while (true);
	}

    /* ***********************
     * Metodo: updateWaves
     * Funcao: Atualiza a distancia percorrida por cada onda inimiga e remove as que passaram
     * Parametros: nenhum
     * Retorno: void
     ********************** */
	public void updateWaves() {
		// Itera sobre todas as ondas inimigas
		for (int i = 0; i < enemyWaves.size(); i++) {
			EnemyWave ew = (EnemyWave)enemyWaves.get(i);
			// Calcula variacao de tempo desde o disparo
            double timeVariation = getTime() - ew.fireTime;
			// Atualiza distancia percorrida pela onda
			ew.distanceTraveled = timeVariation * ew.bulletVelocity;
			// Se a onda passou do robo com folga
			if (ew.distanceTraveled >
				myLocation.distance(ew.fireLocation) + 50) {
				// Remove a onda da lista
				enemyWaves.remove(i);
				// Ajusta indice apos remocao
				i--;
			}
		}
    }

    /* ***********************
     * Metodo: doSurfing
     * Funcao: Executa a estrategia de wave surfing para desviar de tiros
     * Parametros: nenhum
     * Retorno: void
     ********************** */
	public void doSurfing() {
		// Obtem a onda surfavel mais proxima
        EnemyWave surfWave = getClosestSurfableWave();

		// Se nao ha ondas surfaveis, retorna
        if (surfWave == null) {
            return;
        }

		// Calcula perigo de ir para esquerda
        double dangerLeft = checkDanger(surfWave, -1);
		// Calcula perigo de ir para direita
        double dangerRight = checkDanger(surfWave, 1);

		// Calcula angulo base para movimento
        double goAngle = absBearing(surfWave.fireLocation, myLocation);
		// Escolhe direcao com menor perigo
        if (dangerLeft < dangerRight) {
			// Aplica suavizacao de parede para esquerda
            goAngle = wallSmoothing(myLocation, goAngle - (A_LITTLE_LESS_THAN_HALF_PI), -1);
        } else {
			// Aplica suavizacao de parede para direita
            goAngle = wallSmoothing(myLocation, goAngle + (A_LITTLE_LESS_THAN_HALF_PI), 1);
        }

		// Define movimento usando traseira como frente
        setBackAsFront(this, goAngle);
    }

    /* ***********************
     * Metodo: getClosestSurfableWave
     * Funcao: Encontra a onda inimiga surfavel mais proxima
     * Parametros: nenhum
     * Retorno: EnemyWave - a onda mais proxima ou null
     ********************** */
	public EnemyWave getClosestSurfableWave() {
		// Inicializa distancia mais proxima como infinito
        double closestDistance = Double.POSITIVE_INFINITY;
		// Inicializa onda surfavel como null
        EnemyWave surfWave = null;

		// Itera sobre todas as ondas inimigas
        for (int i = 0; i < enemyWaves.size(); i++) {
            EnemyWave ew = (EnemyWave)enemyWaves.get(i);
			// Calcula distancia ate a onda
            double distance = myLocation.distance(ew.fireLocation) - ew.distanceTraveled;

			// Se a onda ainda nao passou e esta mais proxima
            if (distance > ew.bulletVelocity && distance < closestDistance) {
                surfWave = ew;
                closestDistance = distance;
            }
        }

        return surfWave;
    }

    /* ***********************
     * Metodo: checkDanger
     * Funcao: Calcula o nivel de perigo para uma direcao especifica de movimento
     * Parametros: surfWave - onda a ser analisada, direction - direcao de movimento
     * Retorno: double - valor de perigo calculado
     ********************** */
	public double checkDanger(EnemyWave surfWave, int direction) {
		// Preve posicao futura
        Point2D.Double predictedPosition = predictPosition(surfWave, direction);
		// Obtem indice do fator
        int centralIndex = getFactorIndex(surfWave, predictedPosition);

		// Calcula distancia da posicao prevista
        double lastPredictedDistance = surfWave.fireLocation.distance(predictedPosition);
		// Inicializa perigo suavizado
        double smoothedDanger = 0;

		// Suaviza perigo considerando bins vizinhos
        for (int i = -2; i <= 2; i++) {
            int currentIndex = centralIndex + i;
            
            if (currentIndex >= 0 && currentIndex < BINS) {
                double weight;
                
				// Define pesos por proximidade
                if (Math.abs(i) == 0) weight = 1.00;
                else if (Math.abs(i) == 1) weight = 0.50;
                else weight = 0.25;

				// Obtem perigo bruto das estatisticas
                double rawDanger = surfStats[surfWave.segDist][surfWave.segVel][currentIndex];
				// Acumula perigo ponderado
                smoothedDanger += (rawDanger * weight);
            }
        }

		// Retorna perigo normalizado pela distancia
        return (smoothedDanger + 0.01 / (Math.abs(centralIndex - (BINS/2)) + 1)) / Math.pow(lastPredictedDistance, 4);
    }

    /* ***********************
     * Metodo: getFactorIndex
     * Funcao: Calcula o indice do fator de desvio para uma posicao alvo
     * Parametros: ew - onda inimiga, targetLocation - localizacao alvo
     * Retorno: int - indice do fator
     ********************** */
	public static int getFactorIndex(EnemyWave ew, Point2D.Double targetLocation) {
		// Calcula angulo de desvio
        double offsetAngle = (absBearing(ew.fireLocation, targetLocation) - ew.directAngle);
		// Normaliza e calcula fator
        double factor = Utils.normalRelativeAngle(offsetAngle) / maxEscapeAngle(ew.bulletVelocity) * ew.direction;

		// Converte fator para indice do bin
        return (int)limit(0,(factor * (BINS/2) + (BINS/2)), BINS - 1);
    }

    /* ***********************
     * Metodo: predictPosition
     * Funcao: Preve a posicao futura do robo considerando movimento e colisoes
     * Parametros: surfWave - onda de referencia, direction - direcao de movimento
     * Retorno: Point2D.Double - posicao prevista
     ********************** */
	public Point2D.Double predictPosition(EnemyWave surfWave, int direction) {
		// Clona posicao atual
        Point2D.Double predictedPosition = (Point2D.Double)myLocation.clone();
		// Obtem velocidade atual
        double predictedVelocity = getVelocity();
		// Obtem angulo atual
        double predictedHeading = getHeadingRadians();
		// Variaveis para controle de movimento
        double maxTurning, moveAngle, moveDir;

		// Contador de iteracoes
        int counter = 0;
		// Flag de interceptacao
        boolean intercepted = false;

		// Simula movimento ate interceptacao ou limite de iteracoes
        do {
			// Calcula angulo de movimento com suavizacao de parede
            moveAngle = wallSmoothing(predictedPosition, absBearing(surfWave.fireLocation, predictedPosition) + (direction * (A_LITTLE_LESS_THAN_HALF_PI)), direction) - predictedHeading;
			// Inicializa direcao como frente
            moveDir = 1;

			// Se o angulo for maior que 90 graus, inverte direcao
            if(Math.cos(moveAngle) < 0) {
                moveAngle += Math.PI;
                moveDir = -1;
            }

			// Normaliza angulo de movimento
            moveAngle = Utils.normalRelativeAngle(moveAngle);

			// Calcula giro maximo exato via API nativa do Robocode (Precise Prediction)
            maxTurning = Rules.getTurnRateRadians(Math.abs(predictedVelocity));
			// Atualiza angulo previsto limitado pelo giro maximo
            predictedHeading = Utils.normalRelativeAngle(predictedHeading + limit(-maxTurning, moveAngle, maxTurning));

			// Atualiza velocidade prevista usando as constantes oficiais da engine:
			// Rules.DECELERATION ao frear/inverter marcha, Rules.ACCELERATION ao acelerar no mesmo sentido
            predictedVelocity += (predictedVelocity * moveDir < 0 ? Rules.DECELERATION * moveDir : Rules.ACCELERATION * moveDir);
			// Limita velocidade ao limite fisico estrito da engine (Rules.MAX_VELOCITY)
            predictedVelocity = limit(-Rules.MAX_VELOCITY, predictedVelocity, Rules.MAX_VELOCITY);

			// Projeta nova posicao
            predictedPosition = project(predictedPosition, predictedHeading, predictedVelocity);

			// Incrementa contador
            counter++;

			// Verifica se a onda interceptou a posicao prevista
            if (predictedPosition.distance(surfWave.fireLocation) - 18 < surfWave.distanceTraveled + (counter * surfWave.bulletVelocity) + surfWave.bulletVelocity) {
                intercepted = true;
            }
        } while(!intercepted && counter < 500);

        return predictedPosition;
    }

    /* ***********************
     * Metodo: onScannedRobot
     * Funcao: Evento disparado quando o radar detecta um robo inimigo
     * Parametros: e - evento de robo escaneado
     * Retorno: void
     ********************** */
	public void onScannedRobot(ScannedRobotEvent e) {
		// Atualiza posicao atual
		myLocation = new Point2D.Double(getX(), getY());

		// Calcula velocidade lateral
        double lateralVelocity = getVelocity()*Math.sin(e.getBearingRadians());
		// Calcula angulo absoluto para o inimigo
        double absBearing = e.getBearingRadians() + getHeadingRadians();

		// Ajusta radar para manter foco no inimigo
        setTurnRadarRightRadians(Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians()) * 2);

		// Determina direcao atual baseada na velocidade lateral
        int currentDir = (lateralVelocity >= 0) ? 1 : -1;

		// Adiciona direcao e angulo as listas de surf
        surfDirections.add(0, new Integer(currentDir));
        surfAbsBearings.add(0, new Double(absBearing + Math.PI));

		// Calcula potencia do tiro inimigo pela queda de energia
        double bulletPower = opponentEnergy - e.getEnergy();

		// Se detectou um tiro valido
        if (bulletPower < 3.01 && bulletPower > 0.09 && surfDirections.size() > 2 && enemyLocation != null) {
            
			// Cria nova onda inimiga
            EnemyWave ew = new EnemyWave();
            ew.fireTime = getTime() - 1;
            ew.bulletVelocity = bulletVelocity(bulletPower);
            ew.distanceTraveled = bulletVelocity(bulletPower);
            ew.direction = ((Integer)surfDirections.get(2)).intValue();
            ew.directAngle = ((Double)surfAbsBearings.get(2)).doubleValue();
            ew.fireLocation = (Point2D.Double)enemyLocation.clone();

			// Calcula distancia e velocidade lateral absoluta
            double dist = myLocation.distance(enemyLocation);
            double absLat = Math.abs(lateralVelocity);

			// Define segmentos da onda
            ew.segDist = getDistSegment(dist);
            ew.segVel = getVelSegment(absLat);

			// Adiciona onda a lista
            enemyWaves.add(ew);
        }

		// Atualiza energia do oponente
        opponentEnergy = e.getEnergy();

		// Atualiza posicao do inimigo
        enemyLocation = project(myLocation, absBearing, e.getDistance());

		// Atualiza ondas e executa surf
        updateWaves();
        doSurfing();

        // Codigo de controle do canhao
		// Define potencia padrao do tiro
        finalBulletPower = 1.9;
		// Aumenta potencia se inimigo esta perto
        if (e.getDistance() < 240) {
            finalBulletPower = 3.0;
        }
		// Limita potencia baseado na energia do inimigo
        finalBulletPower = Math.min(finalBulletPower, e.getEnergy()/4);
		// Limita potencia baseado na propria energia
        finalBulletPower = Math.min(finalBulletPower, getEnergy()/2);
		// Calcula velocidade do tiro
        double bulletSpeed = Rules.getBulletSpeed(finalBulletPower);

		// Atualiza velocidades do inimigo
        lastEnemyVelocity = enemyVelocity;
        enemyVelocity = e.getVelocity();
        absBearing = e.getBearingRadians() + getHeadingRadians();
        myLocation = new Point2D.Double(getX(), getY());
        double enemyDistance = e.getDistance();

		// Calcula velocidade lateral do inimigo
        enemyLateralVelocity = enemyVelocity * Math.sin(e.getHeadingRadians() - absBearing);
		// Atualiza direcao de movimento se ha velocidade lateral
        if (enemyLateralVelocity != 0)
            moveDirection = (enemyLateralVelocity > 0 ? 1 : -1);
		// Calcula envelope de escape
        escapeEnvelope = moveDirection * maxEscapeAngle(bulletSpeed);

		// Calcula distancia ate a parede na direcao de escape
        wallDistance = 1.1;
        while(wallDistance >= 0.1){
            wallDistance -= 0.1;
            Point2D.Double predictedBulletPosition = project(myLocation,absBearing + wallDistance * escapeEnvelope, enemyDistance);
            if(battleField.contains(predictedBulletPosition))
                break;
        }

		// Calcula distancia reversa ate a parede
        reverseWallDistance = 1.1;
        while(reverseWallDistance >= 0.1){
            reverseWallDistance -= 0.1;
            Point2D.Double predictedBulletPosition = project(myLocation,absBearing - reverseWallDistance * escapeEnvelope, enemyDistance);
            if(battleField.contains(predictedBulletPosition))
                break;
        }

		// Calcula tempo de movimento para segmentacao
        double moveTime = bulletSpeed * lastVelocityChangeTime++ / enemyDistance;

		// Se ambos tem energia, processa tiro
        if (e.getEnergy() > 0 && getEnergy() > 0) {
			// Indice de distancia para segmentacao normal
            int distanceIndex = (int) enemyDistance / 240;
			// Indice de distancia para segmentacao rapida
            int fastDistanceIndex = (int) enemyDistance / 360;

			// Indice de proximidade da parede
            int nearWallIndex = (int) (wallDistance * 3);
            int fastNearWallIndex = (int) (wallDistance * 1.5);

			// Indice reverso de proximidade da parede
            int reverseNearWallIndex = (int) (reverseWallDistance * 2);
            int fastReverseNearWallIndex = (int) (reverseWallDistance * 1.25);

			// Indice de velocidade lateral
            int lateralVelocityIndex = (int) Math.abs(enemyLateralVelocity / 2);
            int fastLateralVelocityIndex = (int) Math.abs(enemyLateralVelocity / 2.67);

			// Indice de tempo de movimento
            int moveTimeIndex = moveTime < .4 ? 1 : moveTime < .8 ? 2 : moveTime < 1.2 ? 3 : 4;
            int fastMoveTimeIndex = moveTime < .6 ? 1 : 2;

			// Indice de aceleracao
            int accelerationIndex = (int) Math.round(Math.abs(enemyVelocity) - Math.abs(lastEnemyVelocity));
            if (accelerationIndex != 0){
                accelerationIndex = accelerationIndex > 0 ? 2 : 1;
            }
			// Reseta tempo de mudanca se houve aceleracao
            if (accelerationIndex > 0) {
                lastVelocityChangeTime = 0;
                moveTimeIndex = fastMoveTimeIndex = 0;
            }

            // Verificacao de limites para evitar ArrayIndexOutOfBoundsException
            distanceIndex     = (int) limit(0, distanceIndex, 9);
            fastDistanceIndex = (int) limit(0, fastDistanceIndex, 9);
            nearWallIndex     = (int) limit(0, nearWallIndex, 4);
            fastNearWallIndex = (int) limit(0, fastNearWallIndex, 4);
            reverseNearWallIndex     = (int) limit(0, reverseNearWallIndex, 2);
            fastReverseNearWallIndex = (int) limit(0, fastReverseNearWallIndex, 2);
            lateralVelocityIndex     = (int) limit(0, lateralVelocityIndex, 4);
            fastLateralVelocityIndex = (int) limit(0, fastLateralVelocityIndex, 4);
            accelerationIndex        = (int) limit(0, accelerationIndex, 2);
            moveTimeIndex            = (int) limit(0, moveTimeIndex, 4);
            fastMoveTimeIndex        = (int) limit(0, fastMoveTimeIndex, 7);

			// Cria nova onda de tiro
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

			// Atualiza ondas de tiro existentes
            for (int i = 0; i < gunWaves.size(); i++) {
                GunWave gw = gunWaves.get(i);
                if(gw.update(getTime(), enemyLocation)){
                    gunWaves.remove(gw);
                    i--;
                }
            }

			// Encontra melhor indice de tiro
            int bestIndex = MIDDLE_GUN_FACTOR;
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

			// Calcula giro do canhao
            if (getGunHeat() < getGunCoolingRate() * 3)
                finalGunTurn = Utils.normalRelativeAngle(absBearing - getGunHeadingRadians() + escapeEnvelope * (bestIndex/(double) MIDDLE_GUN_FACTOR - 1));
            else
                finalGunTurn = Utils.normalRelativeAngle(absBearing-getGunHeadingRadians());

			// Executa giro do canhao e disparo
            setTurnGunRightRadians(finalGunTurn);
            if (finalBulletPower > 0) {
                robocode.Bullet firedBullet = setFireBullet(finalBulletPower);
                if (firedBullet != null) {
                    g.real = true;
                }
            }
        }
	}

    /* ***********************
     * Metodo: logHit
     * Funcao: Registra um acerto nas estatisticas de surf
     * Parametros: ew - onda que acertou, targetLocation - local do acerto
     * Retorno: void
     ********************** */
	public void logHit(EnemyWave ew, Point2D.Double targetLocation) {
		// Obtem indice do acerto
        int hitIndex = getFactorIndex(ew, targetLocation);

		// Atualiza estatisticas para todos os segmentos
        for (int d = 0; d < SEG_DIST; d++) {
            for (int v = 0; v < SEG_VEL; v++) {
				// Calcula peso do segmento baseado na similaridade
                double diffDist = Math.abs(ew.segDist - d);
                double diffVel  = Math.abs(ew.segVel - v);
                double segmentWeight = 1.0 / (1.0 + Math.pow(diffDist, 2) + Math.pow(diffVel, 2));
                if (segmentWeight > 0.05) {
                    for (int x = 0; x < BINS; x++) {
						// Calcula peso do bin
                        double binWeight = 1.0 / (Math.pow(hitIndex - x, 2) + 1);
						// Atualiza estatistica com fator de esquecimento
                        surfStats[d][v][x] = (surfStats[d][v][x] * 0.85 + binWeight * segmentWeight) / (0.85 + segmentWeight);
                    }
                }
            }
        }
    }

    /* ***********************
     * Metodo: onHitByBullet
     * Funcao: Evento disparado quando o robo e atingido por um tiro
     * Parametros: e - evento de ser atingido
     * Retorno: void
     ********************** */
	public void onHitByBullet(HitByBulletEvent e) {
		// Se existem ondas registradas
        if (!enemyWaves.isEmpty()) {
			// Obtem localizacao do tiro que acertou
            Point2D.Double hitBulletLocation = new Point2D.Double(e.getBullet().getX(), e.getBullet().getY());
            EnemyWave hitWave = null;

			// Procura a onda correspondente ao tiro
            for (int x = 0; x < enemyWaves.size(); x++) {
                EnemyWave ew = (EnemyWave)enemyWaves.get(x);

				// Verifica se a onda corresponde ao tiro que acertou
                if (Math.abs(ew.distanceTraveled - myLocation.distance(ew.fireLocation)) < 50 && Math.abs(bulletVelocity(e.getBullet().getPower()) - ew.bulletVelocity) < 0.001) {
                    hitWave = ew;
                    break;
                }
            }

			// Se encontrou a onda, registra o acerto
            if (hitWave != null) {
                logHit(hitWave, hitBulletLocation);
                enemyWaves.remove(enemyWaves.lastIndexOf(hitWave));
            }
        }
	}

    /* ***********************
     * Metodo: onBulletHitBullet
     * Funcao: Evento disparado quando um tiro nosso colide com um tiro inimigo no ar (Bullet Shadowing)
     * Parametros: e - evento de colisao entre balas
     * Retorno: void
     ********************** */
	public void onBulletHitBullet(BulletHitBulletEvent e) {
		// Se existem ondas registradas
        if (!enemyWaves.isEmpty()) {
            Bullet hitBullet = e.getHitBullet();
            Point2D.Double collisionPoint = new Point2D.Double(hitBullet.getX(), hitBullet.getY());
            
            EnemyWave bestWave = null;
            double bestDiff = Double.POSITIVE_INFINITY;

            // Varrer todas as ondas em busca da colisao mais precisa
            for (int x = 0; x < enemyWaves.size(); x++) {
                EnemyWave ew = (EnemyWave)enemyWaves.get(x);

                // Deve coincidir a velocidade da bala (potencia) primeiro
                if (Math.abs(bulletVelocity(hitBullet.getPower()) - ew.bulletVelocity) < 0.001) {
                    
                    // Calcula a diferenca de distancia real
                    double diff = Math.abs(ew.distanceTraveled - collisionPoint.distance(ew.fireLocation));
                    
                    // Se a diferenca for a menor ate agora, salva como a melhor onda
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestWave = ew;
                    }
                }
            }

            // Threshold de seguranca ajustado (como sugerido pela analise critica)
            // Removemos a onda se o erro for menor que 20 pixels
            if (bestWave != null && bestDiff < 20) {
                enemyWaves.remove(enemyWaves.lastIndexOf(bestWave));
            }
        }
	}

    /* ***********************
     * Metodo: onBulletHit
     * Funcao: Evento disparado quando um tiro do robo acerta o inimigo
     * Parametros: e - evento de tiro que acertou
     * Retorno: void
     ********************** */
	public void onBulletHit(BulletHitEvent e) {
		// Reduz energia do oponente baseado no dano causado
        opponentEnergy -= Rules.getBulletDamage(e.getBullet().getPower());
    }

    /* ***********************
     * Metodo: wallSmoothing
     * Funcao: Ajusta o angulo de movimento para evitar colisao com paredes
     * Parametros: botLocation - posicao do robo, angle - angulo desejado, orientation - direcao de ajuste
     * Retorno: double - angulo ajustado
     ********************** */
	public double wallSmoothing(Point2D.Double botLocation, double angle, int orientation) {
		// Enquanto a projecao estiver fora do campo, ajusta o angulo
        while (!battleField.contains(project(botLocation, angle, WALL_STICK))) {
            angle += orientation*0.05;
        }
        return angle;
    }

    /* ***********************
     * Metodo: project
     * Funcao: Projeta um ponto a partir de uma origem, angulo e distancia
     * Parametros: sourceLocation - ponto de origem, angle - angulo em radianos, length - distancia
     * Retorno: Point2D.Double - ponto projetado
     ********************** */
    public static Point2D.Double project(Point2D.Double sourceLocation, double angle, double length) {
        return new Point2D.Double(sourceLocation.x + Math.sin(angle) * length, sourceLocation.y + Math.cos(angle) * length);
    }

    /* ***********************
     * Metodo: weightedHitsSmoothing
     * Funcao: Calcula a soma ponderada de hits para suavizacao
     * Parametros: guessFactorIndex - indice do fator estimado, currentSegment - segmento atual
     * Retorno: double - valor suavizado
     ********************** */
    public static double weightedHitsSmoothing(int guessFactorIndex, double[] currentSegment) {
        double weightedSum = 0;
		// Soma ponderada considerando vizinhanca
        for (int i = 1; i < TOTAL_GUN_FACTORS - 1; i++) {
            int distanceToGFIndex = Math.abs(guessFactorIndex - i);
            weightedSum += currentSegment[i] / Math.sqrt(distanceToGFIndex + 1.0);
        }
        return weightedSum;
    }

    /* ***********************
     * Metodo: findGuessFactorIndex
     * Funcao: Encontra o indice do fator de estimativa baseado em angulos
     * Parametros: startAngle - angulo inicial, newAngle - novo angulo, escapeEnvelope - envelope de escape
     * Retorno: int - indice do fator
     ********************** */
    public int findGuessFactorIndex(double startAngle, double newAngle, double escapeEnvelope) {
		// Calcula fator de estimativa
        double guessFactor = Utils.normalRelativeAngle(newAngle - startAngle) / escapeEnvelope;
		// Converte para indice
        int guessFactorIndex = (int)Math.round((1 + guessFactor) * MIDDLE_GUN_FACTOR);
        return (int)limit(1, guessFactorIndex, TOTAL_GUN_FACTORS - 1);
    }

    /* ***********************
     * Metodo: absBearing
     * Funcao: Calcula o angulo absoluto entre dois pontos
     * Parametros: source - ponto de origem, target - ponto alvo
     * Retorno: double - angulo absoluto em radianos
     ********************** */
    public static double absBearing(Point2D.Double source, Point2D.Double target) {
        return Math.atan2(target.x - source.x, target.y - source.y);
    }

    /* ***********************
     * Metodo: limit
     * Funcao: Limita um valor entre um minimo e um maximo
     * Parametros: min - valor minimo, value - valor a ser limitado, max - valor maximo
     * Retorno: double - valor limitado
     ********************** */
    public static double limit(double min, double value, double max) {
        return Math.max(min, Math.min(value, max));
    }

    /* ***********************
     * Metodo: bulletVelocity
     * Funcao: Calcula a velocidade de um tiro baseado na potencia
     * Parametros: power - potencia do tiro
     * Retorno: double - velocidade do tiro
     ********************** */
    public static double bulletVelocity(double power) {
        return (20.0 - (3.0*power));
    }

    /* ***********************
     * Metodo: maxEscapeAngle
     * Funcao: Calcula o angulo maximo de escape para uma velocidade de tiro
     * Parametros: velocity - velocidade do tiro
     * Retorno: double - angulo maximo de escape
     ********************** */
    public static double maxEscapeAngle(double velocity) {
        return Math.asin(8.0/velocity);
    }

    /* ***********************
     * Metodo: setBackAsFront
     * Funcao: Move o robo na direcao especificada usando a traseira como frente se necessario
     * Parametros: robot - referencia ao robo, goAngle - angulo desejado de movimento
     * Retorno: void
     ********************** */
    public static void setBackAsFront(AdvancedRobot robot, double goAngle) {
		// Calcula diferenca angular
        double angle = Utils.normalRelativeAngle(goAngle - robot.getHeadingRadians());
		// Se o angulo for maior que 90 graus, move de re
        if (Math.abs(angle) > (Math.PI / 2)) {
            if (angle < 0) {
                robot.setTurnRightRadians(Math.PI + angle);
            } else {
                robot.setTurnLeftRadians(Math.PI - angle);
            }
            robot.setBack(100);
        } else {
			// Senao move para frente
            if (angle < 0) {
                robot.setTurnLeftRadians(-1*angle);
           } else {
                robot.setTurnRightRadians(angle);
           }
            robot.setAhead(100);
        }
    }

    // Classe interna para representar uma onda inimiga
	class EnemyWave {
		// Localizacao do disparo
		Point2D.Double fireLocation;
		// Tempo do disparo
		long fireTime;
		// Velocidade do tiro, angulo direto, distancia percorrida
		double bulletVelocity, directAngle, distanceTraveled;
		// Direcao da onda
		int direction;
		// Segmento de distancia
        int segDist;
		// Segmento de velocidade
        int segVel;
    }

    // Classe interna para representar uma onda de tiro propria
    class GunWave {
		// Proxima onda na lista
        GunWave next;
		// Origem do tiro
        Point2D.Double bulletOrigin;
		// Origem do inimigo
        Point2D.Double enemyOrigin;
		// Ultima posicao do inimigo
        Point2D.Double lastEnemyPosition;
		// Angulo do tiro
        double bulletAngle;
		// Velocidade do tiro
        double bulletVelocity;
		// Envelope de escape
        double escapeEnvelope;
		// Tempo do disparo
        long fireTime;
		// Ultimo tempo de atualizacao
        long lastTime;
		// Segmento normal
        double[] normalSegment;
		// Segmento rapido
        double[] fastSegment;
		// Flag se o tiro foi real
        boolean real = false;

		/* ***********************
		 * Metodo: update
		 * Funcao: Atualiza a onda de tiro e verifica se acertou o inimigo
		 * Parametros: time - tempo atual, currentEnemyPosition - posicao atual do inimigo
		 * Retorno: boolean - true se a onda acertou o inimigo
		 ********************** */
        boolean update(long time, Point2D currentEnemyPosition) {
			// Calcula delta de tempo
            long deltaTime = time - lastTime;
			// Calcula velocidade media do inimigo
            double deltaX = (currentEnemyPosition.getX() - lastEnemyPosition.getX()) / deltaTime;
            double deltaY = (currentEnemyPosition.getY() - lastEnemyPosition.getY()) / deltaTime;
            do {
				// Verifica se o tiro alcancou o inimigo
                if (bulletOrigin.distance(lastEnemyPosition) <= bulletVelocity * (lastTime - fireTime)) {
					// Encontra indice do fator de acerto
                    int index = findGuessFactorIndex(bulletAngle, absBearing(bulletOrigin, lastEnemyPosition), escapeEnvelope);
                    index = (int) limit(1, index, MIDDLE_GUN_FACTOR * 2 - 1);
					// Define peso baseado se o tiro foi real
                    double weightReal = real ? 5 : 1;
					// Atualiza segmentos normal e rapido
                    for (int i = 1; i < MIDDLE_GUN_FACTOR * 2; i++) {
                        normalSegment[i] *= normalSegment[0] / (normalSegment[0] + weightReal);
                        fastSegment[i] *= fastSegment[0] / (fastSegment[0] + weightReal);
                    }
                    normalSegment[0] += weightReal;
                    normalSegment[index] += (weightReal / normalSegment[0]);
                    fastSegment[0] += weightReal;
                    fastSegment[index] += (weightReal / fastSegment[0]);
                    return true;
                }
				// Avanca um passo na simulacao
                lastTime++;
                lastEnemyPosition.setLocation(lastEnemyPosition.getX() + deltaX, lastEnemyPosition.getY() + deltaY);
            }
            while (lastTime < time);
            return false;
        }
    }

    /* ***********************
     * Metodo: getDistSegment
     * Funcao: Determina o segmento de distancia baseado na distancia
     * Parametros: distance - distancia ate o inimigo
     * Retorno: int - indice do segmento
     ********************** */
    public static int getDistSegment(double distance) {
        if (distance < 150) return 0;
        if (distance < 300) return 1;
        if (distance < 500) return 2;
        return 3;
    }

    /* ***********************
     * Metodo: getVelSegment
     * Funcao: Determina o segmento de velocidade lateral
     * Parametros: absLateralVelocity - velocidade lateral absoluta
     * Retorno: int - indice do segmento
     ********************** */
    public static int getVelSegment(double absLateralVelocity) {
        if (absLateralVelocity < 0.5) return 0;
        if (absLateralVelocity < 2.0) return 1;
        if (absLateralVelocity < 4.5) return 2;
        if (absLateralVelocity < 7.0) return 3;
        return 4;
    }
}