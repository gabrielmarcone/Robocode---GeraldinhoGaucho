import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.geom.*;
import java.util.ArrayList;

public class GeraldinhoGaucho extends AdvancedRobot {
	// ========== Wave Surfing ==========
	public static int BINS = 47;
    public static double surfStats[] = new double[BINS];
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

			ew.distanceTraveled = (getTime() - ew.fireTime) * ew.bulletVelocity;
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

        double goAngle = absoluteBearing(surfWave.fireLocation, myLocation);
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

        for (int x = 0; x < enemyWaves.size(); x++) {
            EnemyWave ew = (EnemyWave)enemyWaves.get(x);
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
        int index = getFactorIndex(surfWave, predictPosition(surfWave, direction));

        return surfStats[index];
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
        double offsetAngle = (absoluteBearing(ew.fireLocation, targetLocation) - ew.directAngle);
        double factor = Utils.normalRelativeAngle(offsetAngle) / maxEscapeAngle(ew.bulletVelocity) * ew.direction;

        return (int)limit(0, (factor * ((BINS - 1) / 2)) + ((BINS - 1) / 2), BINS - 1);
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

        int counter = 0; // number of ticks in the future
        boolean intercepted = false;

        do {    // the rest of these code comments are rozu's
            moveAngle = wallSmoothing(predictedPosition, absoluteBearing(surfWave.fireLocation, predictedPosition) + (direction * (A_LITTLE_LESS_THAN_HALF_PI)), direction) - predictedHeading;
            moveDir = 1;

            if(Math.cos(moveAngle) < 0) {
                moveAngle += Math.PI;
                moveDir = -1;
            }

            moveAngle = Utils.normalRelativeAngle(moveAngle);

            // maxTurning is built in like this, you can't turn more then this in one tick
            maxTurning = Math.PI/720d*(40d - 3d*Math.abs(predictedVelocity));
            predictedHeading = Utils.normalRelativeAngle(predictedHeading + limit(-maxTurning, moveAngle, maxTurning));

            // this one is nice ;). if predictedVelocity and moveDir have
            // different signs you want to breack down
            // otherwise you want to accelerate (look at the factor "2")
            predictedVelocity += (predictedVelocity * moveDir < 0 ? 2 * moveDir : moveDir);
            predictedVelocity = limit(-8, predictedVelocity, 8);

            // calculate the new predicted position
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

        setTurnRadarRightRadians(Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians()) * 2);

        surfDirections.add(0, new Integer((lateralVelocity >= 0) ? 1 : -1));
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

            enemyWaves.add(ew);
        }

        opponentEnergy = e.getEnergy();

        // update after EnemyWave detection, because that needs the previous
        // enemy location as the source of the wave
        enemyLocation = project(myLocation, absBearing, e.getDistance());

        updateWaves();
        doSurfing();

        // gun code would go here...
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
        int index = getFactorIndex(ew, targetLocation);

        for (int x = 0; x < BINS; x++) {
            // for the spot bin that we were hit on, add 1;
            // for the bins next to it, add 1 / 2;
            // the next one, add 1 / 5; and so on...
            surfStats[x] += 1.0 / (Math.pow(index - x, 2) + 1);
        }
    }

	/* ***************************************************************
	* Metodo: onHitByBullet
	* Funcao: Metodo chamado quando o robo e atingido por um tiro. Responsavel por reagir ao ataque, como mudar de direcao ou velocidade.
	* Parametros: HitByBulletEvent e - evento que contem informacoes sobre o tiro recebido.
	* Retorno: void
	*************************************************************** */
	public void onHitByBullet(HitByBulletEvent e) {
		// If the enemyWaves collection is empty, we must have missed the
        // detection of this wave somehow.
        if (!enemyWaves.isEmpty()) {
            Point2D.Double hitBulletLocation = new Point2D.Double(e.getBullet().getX(), e.getBullet().getY());
            EnemyWave hitWave = null;

            // look through the EnemyWaves, and find one that could've hit us.
            for (int x = 0; x < enemyWaves.size(); x++) {
                EnemyWave ew = (EnemyWave)enemyWaves.get(x);

                if (Math.abs(ew.distanceTraveled - myLocation.distance(ew.fireLocation)) < 50 && Math.abs(bulletVelocity(e.getBullet().getPower()) - ew.bulletVelocity) < 0.001) {
                    hitWave = ew;
                    break;
                }
            }

            if (hitWave != null) {
                logHit(hitWave, hitBulletLocation);

                // We can remove this wave now, of course.
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
    public static Point2D.Double project(Point2D.Double sourceLocation,
        double angle, double length) {
        return new Point2D.Double(sourceLocation.x + Math.sin(angle) * length, sourceLocation.y + Math.cos(angle) * length);
    }

    /* ***************************************************************
    * Metodo: absoluteBearing
    * Funcao: Metodo responsavel por calcular o angulo absoluto entre duas posicoes, utilizado para determinar a direcao de inimigos e tiros.
    * Parametros: Point2D.Double source - a posicao de origem; Point2D.Double target - a posicao de destino.
    * Retorno: double - o angulo absoluto entre as duas posicoes, utilizado para determinar a direcao de inimigos e tiros.
    ***************************************************************** */
    public static double absoluteBearing(Point2D.Double source, Point2D.Double target) {
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
    * Metodo: onBulletFired
    * Funcao: Registra cada tiro disparado pelo robo.
    * Parametros: BulletFiredEvent e - evento do disparo.
    * Retorno: void
    *************************************************************** */
    //public void onBulletFired(BulletFiredEvent e) {
    //    shotsFired++;
    //}

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
    }
}