import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.geom.*;
import java.util.ArrayList;

public class GeraldinhoGaucho extends AdvancedRobot {
	// Variaveis para a tecnica de Wave Surfing
	public static int BINS = 47;
    public static double _surfStats[] = new double[BINS];
	private Point2D.Double myLocation;
	private Point2D.Double enemyLocation;
	public ArrayList _enemyWaves;
    public ArrayList _surfDirections;
    public ArrayList _surfAbsBearings;

    public static double _oppEnergy = 100.0;

	private Rectangle2D battleField;
    public static double WALL_STICK = 160;

	private final double WALL_MARGIN = 36;
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
		_surfDirections = new ArrayList<>();
		_surfAbsBearings = new ArrayList<>();
		_enemyWaves = new ArrayList<>();

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
		for (int x = 0; x < _enemyWaves.size(); x++) {
			EnemyWave ew = (EnemyWave)_enemyWaves.get(x);

			ew.distanceTraveled = (getTime() - ew.fireTime) * ew.bulletVelocity;
			if (ew.distanceTraveled >
				myLocation.distance(ew.fireLocation) + 50) {
				_enemyWaves.remove(x);
				x--;
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
            goAngle = wallSmoothing(myLocation, goAngle - (Math.PI/2), -1);
        } else {
            goAngle = wallSmoothing(myLocation, goAngle + (Math.PI/2), 1);
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

        for (int x = 0; x < _enemyWaves.size(); x++) {
            EnemyWave ew = (EnemyWave)_enemyWaves.get(x);
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

        return _surfStats[index];
    }

	public static int getFactorIndex(EnemyWave ew, Point2D.Double targetLocation) {
        double offsetAngle = (absoluteBearing(ew.fireLocation, targetLocation) - ew.directAngle);
        double factor = Utils.normalRelativeAngle(offsetAngle) / maxEscapeAngle(ew.bulletVelocity) * ew.direction;

        return (int)limit(0, (factor * ((BINS - 1) / 2)) + ((BINS - 1) / 2), BINS - 1);
    }

	public Point2D.Double predictPosition(EnemyWave surfWave, int direction) {
        Point2D.Double predictedPosition = (Point2D.Double)myLocation.clone();
        double predictedVelocity = getVelocity();
        double predictedHeading = getHeadingRadians();
        double maxTurning, moveAngle, moveDir;

        int counter = 0; // number of ticks in the future
        boolean intercepted = false;

        do {    // the rest of these code comments are rozu's
            moveAngle = wallSmoothing(predictedPosition, absoluteBearing(surfWave.fireLocation, predictedPosition) + (direction * (Math.PI/2)), direction) - predictedHeading;
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

        double lateralVelocity = getVelocity()*Math.sin(e.getBearingRadians());
        double absBearing = e.getBearingRadians() + getHeadingRadians();

        setTurnRadarRightRadians(Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians()) * 2);

        _surfDirections.add(0, new Integer((lateralVelocity >= 0) ? 1 : -1));
        _surfAbsBearings.add(0, new Double(absBearing + Math.PI));


        double bulletPower = _oppEnergy - e.getEnergy();

        if (bulletPower < 3.01 && bulletPower > 0.09 && _surfDirections.size() > 2) {
            EnemyWave ew = new EnemyWave();
            ew.fireTime = getTime() - 1;
            ew.bulletVelocity = bulletVelocity(bulletPower);
            ew.distanceTraveled = bulletVelocity(bulletPower);
            ew.direction = ((Integer)_surfDirections.get(2)).intValue();
            ew.directAngle = ((Double)_surfAbsBearings.get(2)).doubleValue();
            ew.fireLocation = (Point2D.Double)enemyLocation.clone(); // last tick

            _enemyWaves.add(ew);
        }

        _oppEnergy = e.getEnergy();

        // update after EnemyWave detection, because that needs the previous
        // enemy location as the source of the wave
        enemyLocation = project(myLocation, absBearing, e.getDistance());

        updateWaves();
        doSurfing();

        // gun code would go here...
	}

	public void logHit(EnemyWave ew, Point2D.Double targetLocation) {
        int index = getFactorIndex(ew, targetLocation);

        for (int x = 0; x < BINS; x++) {
            // for the spot bin that we were hit on, add 1;
            // for the bins next to it, add 1 / 2;
            // the next one, add 1 / 5; and so on...
            _surfStats[x] += 1.0 / (Math.pow(index - x, 2) + 1);
        }
    }

	/* ***************************************************************
	* Metodo: onHitByBullet
	* Funcao: Metodo chamado quando o robo e atingido por um tiro. Responsavel por reagir ao ataque, como mudar de direcao ou velocidade.
	* Parametros: HitByBulletEvent e - evento que contem informacoes sobre o tiro recebido.
	* Retorno: void
	*************************************************************** */
	public void onHitByBullet(HitByBulletEvent e) {
		// If the _enemyWaves collection is empty, we must have missed the
        // detection of this wave somehow.
        if (!_enemyWaves.isEmpty()) {
            Point2D.Double hitBulletLocation = new Point2D.Double(e.getBullet().getX(), e.getBullet().getY());
            EnemyWave hitWave = null;

            // look through the EnemyWaves, and find one that could've hit us.
            for (int x = 0; x < _enemyWaves.size(); x++) {
                EnemyWave ew = (EnemyWave)_enemyWaves.get(x);

                if (Math.abs(ew.distanceTraveled - myLocation.distance(ew.fireLocation)) < 50 && Math.abs(bulletVelocity(e.getBullet().getPower()) - ew.bulletVelocity) < 0.001) {
                    hitWave = ew;
                    break;
                }
            }

            if (hitWave != null) {
                logHit(hitWave, hitBulletLocation);

                // We can remove this wave now, of course.
                _enemyWaves.remove(_enemyWaves.lastIndexOf(hitWave));
            }
        }
	}

	public double wallSmoothing(Point2D.Double botLocation, double angle, int orientation) {
        while (!battleField.contains(project(botLocation, angle, WALL_STICK))) {
            angle += orientation*0.05;
        }
        return angle;
    }

    public static Point2D.Double project(Point2D.Double sourceLocation,
        double angle, double length) {
        return new Point2D.Double(sourceLocation.x + Math.sin(angle) * length, sourceLocation.y + Math.cos(angle) * length);
    }

    public static double absoluteBearing(Point2D.Double source, Point2D.Double target) {
        return Math.atan2(target.x - source.x, target.y - source.y);
    }

    public static double limit(double min, double value, double max) {
        return Math.max(min, Math.min(value, max));
    }

    public static double bulletVelocity(double power) {
        return (20.0 - (3.0*power));
    }

    public static double maxEscapeAngle(double velocity) {
        return Math.asin(8.0/velocity);
    }

    public static void setBackAsFront(AdvancedRobot robot, double goAngle) {
        double angle = Utils.normalRelativeAngle(goAngle - robot.getHeadingRadians());
        if (Math.abs(angle) > (Math.PI/2)) {
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

	class EnemyWave {
		Point2D.Double fireLocation;
		long fireTime;
		double bulletVelocity, directAngle, distanceTraveled;
		int direction;
    }
}