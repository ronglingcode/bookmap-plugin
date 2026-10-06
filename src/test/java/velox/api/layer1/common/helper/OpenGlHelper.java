package velox.api.layer1.common.helper;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/** Headless test fixture for a Bookmap runtime helper omitted from its public API JAR.
 * Tests inspect canvas coordinates and raster images, not actual OpenGL upload/format.
 * This source belongs only to the test source set and must never enter the plugin JAR.
 */
public final class OpenGlHelper {
    private OpenGlHelper() {}
    public static Future<int[]> convertImageToOpenGlFormatAsync(BufferedImage image) {
        return CompletableFuture.completedFuture(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
    }
}
