package it.cnr.isti.wnlab.indoornavigation.android.compass;

import android.hardware.SensorManager;
import android.os.Handler;

import java.util.Timer;
import java.util.TimerTask;

import it.cnr.isti.wnlab.indoornavigation.Compass;
import it.cnr.isti.wnlab.indoornavigation.observer.DataEmitter;
import it.cnr.isti.wnlab.indoornavigation.observer.DataObserver;
import it.cnr.isti.wnlab.indoornavigation.types.inertial.Acceleration;
import it.cnr.isti.wnlab.indoornavigation.types.inertial.AngularSpeed;
import it.cnr.isti.wnlab.indoornavigation.types.Heading;
import it.cnr.isti.wnlab.indoornavigation.types.environmental.MagneticField;

/**
 * This class represents a compass derived from low-filtered Accelerometer and Magnetometer and
 * high-filtered gyroscope.
 * The output is the rotation of the phone from the north, between -pi and pi.
 *
 * Code is an adaptation of https://www.codeproject.com/Articles/729759/Android-Sensor-Fusion-Tutorial
 * @author Paul Lawitzki, Michele Agostini (adaptment)
 */

public class LawitzkiCompass extends Compass {

    private static float[] multiplication3x3(float[] A, float[] B) {
        float[] result = new float[9];

        result[0] = A[0] * B[0] + A[1] * B[3] + A[2] * B[6];
        result[1] = A[0] * B[1] + A[1] * B[4] + A[2] * B[7];
        result[2] = A[0] * B[2] + A[1] * B[5] + A[2] * B[8];

        result[3] = A[3] * B[0] + A[4] * B[3] + A[5] * B[6];
        result[4] = A[3] * B[1] + A[4] * B[4] + A[5] * B[7];
        result[5] = A[3] * B[2] + A[4] * B[5] + A[5] * B[8];

        result[6] = A[6] * B[0] + A[7] * B[3] + A[8] * B[6];
        result[7] = A[6] * B[1] + A[7] * B[4] + A[8] * B[7];
        result[8] = A[6] * B[2] + A[7] * B[5] + A[8] * B[8];

        return result;
    }

    private static float[] newIdentity(int r, int c) {
        int size = r*c;
        float[] identity = new float[size];
        for(int i = 0; i < size; i++) {
            if(i%r == 0)
                identity[i] = 1.f;
            else
                identity[0] = 0.f;
        }
        return identity;
    }

    /*
     * Handlers and configuration
     */

    private DataEmitter<Acceleration> accelerometer;
    private DataEmitter<AngularSpeed> gyroscope;
    private DataEmitter<MagneticField> magnetometer;

    // Compass configuration
    private int mRate;

    // For android multithreading
    private Handler mHandler;

    // Updates' timer
    private Timer mTimer;


    /*
     * Gyroscope (high-pass filtered)
     */

    // Rotation matrix from gyro data
    private float[] gyroMatrix = new float[9];

    // Orientation angles from gyro matrix
    private float[] gyroOrientation = new float[3];

    /*
     * Magnetic Field and Acceleration (low-pass filtered)
     */

    // Magnetic field
    private MagneticField magnet;

    // Accelerometer vector
    private Acceleration accel;

    // Orientation angles from accel and magnet
    private float[] accMagOrientation = new float[3];

    // Accelerometer and magnetometer based rotation matrix
    private float[] rotationMatrix = new float[9];

    /*
     * Fusion
     */

    // Coefficient for sensor fusion
    private static final float FILTER_COEFFICIENT = 0.98f;

    public LawitzkiCompass(DataEmitter<Acceleration> accelerometer,
                           DataEmitter<AngularSpeed> gyroscope,
                           DataEmitter<MagneticField> magnetometer,
                           int rate
    ) {
        // Rate for updating
        mRate = rate;

        this.accelerometer = accelerometer;
        this.gyroscope = gyroscope;
        this.magnetometer = magnetometer;
    }

    @Override
    protected void startEmission() {
        gyroOrientation[0] = 0.0f;
        gyroOrientation[1] = 0.0f;
        gyroOrientation[2] = 0.0f;

        // initialise gyroMatrix with identity matrix
        gyroMatrix[0] = 1.0f; gyroMatrix[1] = 0.0f; gyroMatrix[2] = 0.0f;
        gyroMatrix[3] = 0.0f; gyroMatrix[4] = 1.0f; gyroMatrix[5] = 0.0f;
        gyroMatrix[6] = 0.0f; gyroMatrix[7] = 0.0f; gyroMatrix[8] = 1.0f;

        // Attach to sources
        accelerometer.register(new DataObserver<Acceleration>() {
            @Override
            public void notify(Acceleration data) {
                onAccelerometer(data);
            }
        });
        magnetometer.register(new DataObserver<MagneticField>() {
            @Override
            public void notify(MagneticField data) {
                onMagnetometer(data);
            }
        });
        gyroscope.register(new DataObserver<AngularSpeed>() {
            @Override
            public void notify(AngularSpeed data) {
                onGyroscope(data);
            }
        });

        // Initialize timed action
        mTimer = new Timer();
        mHandler = new Handler();
        mTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        calculateFusedOrientation();
                    }
                });
            }
        }, 0, mRate);
    }

    @Override
    protected void stopEmission() {
        mTimer.cancel();
    }

    /**
     * Reset scheduled task at specified rate.
     * @param rate New rate for compass's timer.
     */
    public void setRate(int rate) {
        mRate = rate;
        mTimer.cancel();
        mTimer = new Timer();
        mTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        calculateFusedOrientation();
                    }
                });
            }
        }, 0, mRate);
    }

    // Accelerometer

    /**
     * Saves new accelerometer data and try calculating the orientation from acceleration and mf.
     * @param data
     */
    private void onAccelerometer(Acceleration data) {
        accel = data;
        calculateAccMagOrientation();
    }

    /**
     * Saves MF updates.
     * @param data
     */
    private void onMagnetometer(MagneticField data) {
        magnet = data;
    }

    /**
     * If there's acceleration and magnetic data, fuse them.
     */
    private void calculateAccMagOrientation() {
        if(accel != null && magnet != null && SensorManager.getRotationMatrix(rotationMatrix, null, accel.getArray(), magnet.getArray())) {
            SensorManager.getOrientation(rotationMatrix, accMagOrientation);
        }
    }

    /* ***************************************
     * GYROSCOPE
     * ***************************************/

    private static final float NS2S = 1.0f / 1000000000.0f;
    private long timestamp;
    private boolean initState = true;

    private void onGyroscope(AngularSpeed data) {
        // don't startEmission until first accelerometer/magnetometer orientation has been acquired
        if (accMagOrientation == null)
            return;

        // initialisation of the gyroscope based rotation matrix
        if(initState) {
            float[] initMatrix = getRotationMatrixFromOrientation(accMagOrientation);
            float[] test = new float[3];
            SensorManager.getOrientation(initMatrix, test);
            gyroMatrix = multiplication3x3(gyroMatrix, initMatrix);
            initState = false;
        }

        AngularSpeed gyro;

        // copy the new gyro values into the gyro array
        // convert the raw gyro data into a rotation vector
        float[] deltaVector = new float[4];
        if(timestamp != 0) {
            final float dT = (data.timestamp - timestamp) * NS2S;
            gyro = data;
            getRotationVectorFromGyro(gyro, deltaVector, dT / 2.0f);
        }

        // measurement done, save current time for next interval
        timestamp = data.timestamp;

        // convert rotation vector into rotation matrix
        float[] deltaMatrix = new float[9];
        SensorManager.getRotationMatrixFromVector(deltaMatrix, deltaVector);

        // apply the new rotation interval on the gyroscope based rotation matrix
        gyroMatrix = multiplication3x3(gyroMatrix, deltaMatrix);

        // get the gyroscope based orientation from the rotation matrix
        SensorManager.getOrientation(gyroMatrix, gyroOrientation);
    }

    /* ***************************************
     * FUSION
     * ***************************************/

    private void calculateFusedOrientation() {

        // final orientation angles from sensor fusion
        float[] fusedOrientation = new float[3];

        float oneMinusCoeff = 1.0f - FILTER_COEFFICIENT;
        fusedOrientation[0] =
                FILTER_COEFFICIENT * gyroOrientation[0]
                        + oneMinusCoeff * accMagOrientation[0];

        fusedOrientation[1] =
                FILTER_COEFFICIENT * gyroOrientation[1]
                        + oneMinusCoeff * accMagOrientation[1];

        fusedOrientation[2] =
                FILTER_COEFFICIENT * gyroOrientation[2]
                        + oneMinusCoeff * accMagOrientation[2];

        // overwrite gyro matrix and orientation with fused orientation
        // to compensate gyro drift
        gyroMatrix = getRotationMatrixFromOrientation(fusedOrientation);
        System.arraycopy(fusedOrientation, 0, gyroOrientation, 0, 3);

        // Update heading
        onHeadingChange(fusedOrientation[0], timestamp);
    }

    /**
     * Notify observers on heading changes.
     * @param heading New heading.
     * @param timestamp Timestamp of last gyroscope measure.
     */
    protected void onHeadingChange(float heading, long timestamp) {
        notifyObservers(new Heading(heading, timestamp));
    }

    /* ***************************************
     * UTILITY METHODS
     * ***************************************/

    private static void getRotationVectorFromGyro(AngularSpeed gyroValues, float[] deltaRotationVector, float timeFactor) {

        float[] normValues = new float[3];

        // Calculate the angular speed of the sample
        float omegaMagnitude =
                (float)Math.sqrt(
                        gyroValues.x * gyroValues.x +
                                gyroValues.y * gyroValues.y +
                                gyroValues.z * gyroValues.z);

        // Normalize the rotation vector if it's big enough to get the axis
        final float EPSILON = 0.000000001f;
        if(omegaMagnitude > EPSILON) {
            normValues[0] = gyroValues.x / omegaMagnitude;
            normValues[1] = gyroValues.y / omegaMagnitude;
            normValues[2] = gyroValues.z / omegaMagnitude;
        }

        // Integrate around this axis with the angular speed by the timestep
        // in order to get a delta rotation from this sample over the timestep
        // We will convert this axis-angle representation of the delta rotation
        // into a quaternion before turning it into the rotation matrix.
        float thetaOverTwo = omegaMagnitude * timeFactor;
        float sinThetaOverTwo = (float)Math.sin(thetaOverTwo);
        float cosThetaOverTwo = (float)Math.cos(thetaOverTwo);
        deltaRotationVector[0] = sinThetaOverTwo * normValues[0];
        deltaRotationVector[1] = sinThetaOverTwo * normValues[1];
        deltaRotationVector[2] = sinThetaOverTwo * normValues[2];
        deltaRotationVector[3] = cosThetaOverTwo;
    }

    private static float[] getRotationMatrixFromOrientation(float[] orientation) {
        float[] xM = new float[9];
        float[] yM = new float[9];
        float[] zM = new float[9];

        float sinX = (float)Math.sin(orientation[1]);
        float cosX = (float)Math.cos(orientation[1]);
        float sinY = (float)Math.sin(orientation[2]);
        float cosY = (float)Math.cos(orientation[2]);
        float sinZ = (float)Math.sin(orientation[0]);
        float cosZ = (float)Math.cos(orientation[0]);

        // rotation about x-axis (pitch)
        xM[0] = 1.0f; xM[1] = 0.0f; xM[2] = 0.0f;
        xM[3] = 0.0f; xM[4] = cosX; xM[5] = sinX;
        xM[6] = 0.0f; xM[7] = -sinX; xM[8] = cosX;

        // rotation about y-axis (roll)
        yM[0] = cosY; yM[1] = 0.0f; yM[2] = sinY;
        yM[3] = 0.0f; yM[4] = 1.0f; yM[5] = 0.0f;
        yM[6] = -sinY; yM[7] = 0.0f; yM[8] = cosY;

        // rotation about z-axis (azimuth)
        zM[0] = cosZ; zM[1] = sinZ; zM[2] = 0.0f;
        zM[3] = -sinZ; zM[4] = cosZ; zM[5] = 0.0f;
        zM[6] = 0.0f; zM[7] = 0.0f; zM[8] = 1.0f;

        // rotation order is y, x, z (roll, pitch, azimuth)
        float[] resultMatrix = multiplication3x3(xM, yM);
        resultMatrix = multiplication3x3(zM, resultMatrix);
        return resultMatrix;
    }

}
