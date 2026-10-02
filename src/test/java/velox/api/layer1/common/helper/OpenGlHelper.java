package velox.api.layer1.common.helper;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/** Test-only stand-in for the Bookmap runtime image converter. */
public final class OpenGlHelper {
    private OpenGlHelper() { }

    public static Future<int[]> convertImageToOpenGlFormatAsync(BufferedImage image) {
        return CompletableFuture.completedFuture(new int[0]);
    }
}
