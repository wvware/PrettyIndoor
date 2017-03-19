package it.cnr.isti.wnlab.indoornavigation.javaonly.types.fingerprint;

import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public abstract class FingerprintBuilder<F extends Fingerprint> {
    /**
     * @param file The fingerprint file.
     * @return A ready-to-use Fingerprint instance.
     */
    public F build(File file) {
        try {
            return build(Files.readLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * @param lines
     * @return
     */
    protected abstract F build(List<String> lines);
}