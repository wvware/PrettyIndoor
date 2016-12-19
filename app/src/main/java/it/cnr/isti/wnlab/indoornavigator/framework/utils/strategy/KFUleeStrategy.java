package it.cnr.isti.wnlab.indoornavigator.framework.utils.strategy;

import it.cnr.isti.wnlab.indoornavigator.framework.IndoorPosition;
import it.cnr.isti.wnlab.indoornavigator.framework.LocationStrategy;
import it.cnr.isti.wnlab.indoornavigator.framework.Observer;
import it.cnr.isti.wnlab.indoornavigator.framework.kalmanfilter.IndoorKalmanFilter;
import it.cnr.isti.wnlab.indoornavigator.framework.kalmanfilter.adapters.LocationStrategyUpdater;
import it.cnr.isti.wnlab.indoornavigator.framework.kalmanfilter.adapters.PDRPredictor;
import it.cnr.isti.wnlab.indoornavigator.framework.utils.geomagnetic.mm.MagneticMismatchLocator;
import it.cnr.isti.wnlab.indoornavigator.framework.utils.intertial.pdr.PDR;
import it.cnr.isti.wnlab.indoornavigator.framework.utils.wifi.fingerprint.WifiFingerprintLocator;

/**
 * Implementation of strategy used by You Li.
 */
public class KFUleeStrategy extends LocationStrategy {

    private final static float STEP_LENGTH = 0.5f;

    private final static float INI_POS_VAR = 5.f;
    private final static float INI_HEAD_VAR = (float) Math.toRadians(10);

    private final static float POS_PHASE = 1.f;
    private final static float HEAD_PHASE = (float) Math.toRadians(3);

    // Kalman Filter
    private IndoorKalmanFilter kf;
    private PDRPredictor pdr2kf;
    private LocationStrategyUpdater loc2kf;

    // Floor
    private int mFloor;

    // Step count
    private int mStepCount;
    private static final int STEP_LIMIT = 3;

    public KFUleeStrategy(
            IndoorPosition startPosition,
            PDR pdr,
            WifiFingerprintLocator wifiLocator,
            MagneticMismatchLocator mmLocator
    ) {
        // Kalman Filter implementation
        kf = initKF(startPosition);

        // Floor initialization
        mFloor = startPosition.floor;

        // KF prediction with PDR
        pdr2kf = new PDRPredictor(kf, STEP_LENGTH);
        pdr.register(new Observer<PDR.Result>() {
            @Override
            public void notify(PDR.Result pdrDelta) {
                pdr2kf.predict(pdrDelta);
                notifyObservers(kf.getPosition(mFloor,pdrDelta.timestamp));
                mStepCount++;
                if(mStepCount > STEP_LIMIT) {
                    // Clear pre-prediction positions in updater (for not using old positions)
                    loc2kf.clearPositions();
                }
            }
        });

        // KF update with MM and Wifi Fingerprint
        loc2kf = new LocationStrategyUpdater(kf, wifiLocator, mmLocator);

        // Initialize step count
        mStepCount = 0;
    }

    /**
     * You Li-like Kalman Filter initialization.
     * @param startPosition Position where the user starts from.
     * @return A ready-to-use KF object for indoor localization.
     */
    private IndoorKalmanFilter initKF(IndoorPosition startPosition) {
        // Initial state vector
        double[] x0 = {startPosition.x, startPosition.y, 1, 1};

        // Initial error covariance matrix
        double[][] mP0 = {
                {INI_POS_VAR*INI_POS_VAR, 0, 0, 0},
                {0, INI_POS_VAR*INI_POS_VAR, 0, 0},
                {0, 0, INI_HEAD_VAR*INI_HEAD_VAR, 0},
                {0, 0, 0, STEP_LENGTH*STEP_LENGTH}
        };

        // State transition matrix
        double[][] mA = {
                {1, 0, 0, 0},
                {0, 1, 0, 0},
                {0, 0, 1, 0},
                {0, 0, 0, 1}
        };

        // Control matrix
        double[][] mB = {
                {1, 0, 0, 0},
                {0, 1, 0, 0},
                {0, 0, 1, 0},
                {0, 0, 0, 1}
        };

        // Process noise covariance matrix
        double[][] mQ = {
                {POS_PHASE*POS_PHASE, 0, 0, 0},
                {0, POS_PHASE*POS_PHASE, 0, 0},
                {0, 0, HEAD_PHASE*HEAD_PHASE, 0},
                {0, 0, 0, STEP_LENGTH*STEP_LENGTH}
        };

        // Measurement matrix
        double[][] mH = {
                {1, 0, 0, 0},
                {0, 1, 0, 0},
                {0, 0, 1, 0},
                {0, 0, 0, 1}
        };

        // Measurement noise covariance matrix
        double[][] mR = {
                {0, 0, 0, 0},
                {0, 0, 0, 0},
                {0, 0, 0, 0},
                {0, 0, 0, 0}
        };

        // Return IndoorKF object
        return new IndoorKalmanFilter(x0, mP0, mA, mB, mQ, mH, mR);
    }
}