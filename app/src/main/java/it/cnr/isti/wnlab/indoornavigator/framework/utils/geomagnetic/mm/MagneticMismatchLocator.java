package it.cnr.isti.wnlab.indoornavigator.framework.utils.geomagnetic.mm;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import it.cnr.isti.wnlab.indoornavigator.framework.DataObserver;
import it.cnr.isti.wnlab.indoornavigator.framework.IndoorPosition;
import it.cnr.isti.wnlab.indoornavigator.framework.LocationStrategy;
import it.cnr.isti.wnlab.indoornavigator.framework.XYPosition;
import it.cnr.isti.wnlab.indoornavigator.framework.types.MagneticField;
import it.cnr.isti.wnlab.indoornavigator.framework.utils.Utils;

public class MagneticMismatchLocator extends LocationStrategy implements DataObserver<MagneticField> {

    // Positions (rows)
    private XYPosition[] mAllPositions;

    // Magnetic field measured values
    private MagneticField[] mValues;

    // Selected floor. Note it's constant, so it's needed one WifiFingerprintLocator per floor
    private int mFloor;

    // Limit for K-NN
    private int mK;
    private boolean isKnn = false;

    // Maximum threshold for distance: if position's row distance is over this, it isn't accepted
    private float mThreshold;
    private boolean isThreshold = false;

    /**
     * Custom position subtype.
     */
    private class MMFingerprintPosition extends IndoorPosition {

        public MMFingerprintPosition(XYPosition p, int floor, long timestamp) {
            super(p, floor, timestamp);
        }

        public String toString() {
            return "MAG " + super.toString();
        }
    }

    private MagneticMismatchLocator(XYPosition[] positions, MagneticField[] values, int floor, int knnLimit, float threshold) {
        initialize(positions, values, floor);
        initKNN(knnLimit);
        initThreshold(threshold);
    }

    private void initialize(XYPosition[] positions, MagneticField[] values, int floor) {
        mAllPositions = positions;
        mValues = values;
        mFloor = floor;
    }

    private void initKNN(int knnLimit) {
        mK = knnLimit;
        isKnn = true;
    }

    private void initThreshold(float threshold) {
        mThreshold = threshold;
        isThreshold = true;
    }

    @Override
    public void notify(MagneticField data) {
        IndoorPosition p = localize(data);
        if(p != null)
            notifyObservers(p);
    }

    /**
     * Calculate minimum euclidean distanced row in matrix and find position.
     * @param mf Magnetic field just measured.
     * @return An average position between all K (if minor than infinity) positions which are
     * euclidean distanced less than the threshold (if any).
     */
    private MMFingerprintPosition localize(MagneticField mf) {
        // All K (if minor than infinity) positions which are euclidean distanced less than the threshold (if any).
        List<XYPosition> positions = new ArrayList<>();
        List<Float> distances = new ArrayList<>();

        // Find positions
        for (int i = 0; i < mAllPositions.length; i++) {
            MagneticField v = mValues[i];
            float euclideanDistance = -1;
            if (!isThreshold || (euclideanDistance = checkThreshold(v, mf)) != -1) {
                // Save position if there's no threshold or it is in a valid distance
                positions.add(mAllPositions[i]);
                // If K-NN is active, save euclidean distance
                if(isKnn)
                    distances.add(euclideanDistance);
            }
        }

        // Calculate average between found positions
        if (!positions.isEmpty()) {
            if(isKnn)
                positions = Utils.knn(positions,distances,mK);
            // If positions have been found, return an average one
            return new MMFingerprintPosition(Utils.averagePosition(positions), mFloor, mf.timestamp);
        } else
            return null;
    }

    private float checkThreshold(MagneticField v1, MagneticField v2) {
            float delta = 0.f;

            // Check x
            float diff = v1.x - v2.x;
            delta += diff * diff;

            // Then check y (conditionally)
            if (delta < mThreshold || !isThreshold) {
                diff = v1.y - v2.y;
                delta += diff * diff;

                // Then check z (conditionally)
                if (delta < mThreshold || !isThreshold) {
                    diff = v1.z - v2.z;
                    delta += diff * diff;

                    // If x^2+y^2+z^2 < minDelta, OK!
                    if (delta < mThreshold || !isThreshold) {
                        return delta;
                    }
                }
        }

        return -1.f;
    }

    /**
     * The compatible format for TSV map is:
     *
     * X1\tY1\tMX1\tMY1\tMZ1\n
     * X2\tY2\tMX2\tMY2\tMZ2\n
     * ...
     * XN\tYN\tMXN\tMYN\tMZN
     *
     * NOTE: the table ends without '\n'
     *
     * @return A ready-to-use Magnetic Mismatch locator. Null if an error occurs.
     */
    public static MagneticMismatchLocator makeInstance(
            File tsvWellFormattedRSSI,
            int floor,
            int knnLimit,
            float threshold) {

        try(BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(tsvWellFormattedRSSI)))) {

            // Read and parse all lines of the file
            ArrayList<XYPosition> positions = new ArrayList<>();
            ArrayList<MagneticField> values = new ArrayList<>();
            String r;
            while( (r = br.readLine()) != null ) {
                String[] splitr = r.split("\t");
                positions.add(new XYPosition(Float.parseFloat(splitr[0]), Float.parseFloat(splitr[1])));
                values.add(new MagneticField(Float.parseFloat(splitr[2]), Float.parseFloat(splitr[3]), Float.parseFloat(splitr[4]), -1, -1));
            }

            // Return the ready-to-use MM locator
            XYPosition[] positionArray = positions.toArray(new XYPosition[positions.size()]);
            MagneticField[] valuesArray = values.toArray(new MagneticField[values.size()]);
            return new MagneticMismatchLocator(positionArray, valuesArray, floor, knnLimit, threshold);

        } catch (IOException e) {
            e.printStackTrace();
        } catch (ArrayIndexOutOfBoundsException e) {
            Log.e("WifiFingLocFactory", "File is not well-formatted");
        }

        return null;
    }
}