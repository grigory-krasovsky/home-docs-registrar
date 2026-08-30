package com.example.homedocsregistrar.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Cleans a raster image before OCR by shelling out to ImageMagick: fix EXIF orientation, convert to
 * grayscale, deskew (phone photos are rarely straight) and normalize contrast. Best-effort — if the
 * tool is missing or fails, the caller falls back to the un-processed image, so OCR still runs.
 */
@Component
public class ImagePreprocessor {

    private static final Logger log = LoggerFactory.getLogger(ImagePreprocessor.class);

    private final String magick;

    public ImagePreprocessor(@Value("${ocr.magick:magick}") String magick) {
        this.magick = magick;
    }

    /** Return a cleaned PNG for OCR, or empty when preprocessing is not possible. */
    public Optional<byte[]> process(byte[] input) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("pre-");
            Path in = dir.resolve("in");
            Path out = dir.resolve("out.png");
            Files.write(in, input);

            Process process = new ProcessBuilder(magick, in.toString(),
                    "-auto-orient", "-colorspace", "Gray", "-deskew", "40%", "-normalize", out.toString())
                    .redirectErrorStream(true)
                    .directory(dir.toFile())
                    .start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("ImageMagick preprocessing timed out");
                return Optional.empty();
            }
            if (process.exitValue() != 0 || !Files.exists(out)) {
                log.warn("ImageMagick preprocessing exited with code {}", process.exitValue());
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(out);
            return bytes.length > 0 ? Optional.of(bytes) : Optional.empty();
        } catch (IOException e) {
            log.warn("Image preprocessing failed", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            deleteRecursively(dir);
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }
}
