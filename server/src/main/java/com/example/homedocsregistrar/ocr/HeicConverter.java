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
 * Converts HEIC/HEIF images (what iPhones produce by default) to JPEG by shelling out to libheif's
 * {@code heif-convert}, since Java's ImageIO cannot read HEIC. The tool and the HEVC decoder plugin
 * are installed in the Docker image; without them conversion simply returns empty.
 */
@Component
public class HeicConverter {

    private static final Logger log = LoggerFactory.getLogger(HeicConverter.class);

    private final String heifConvert;

    public HeicConverter(@Value("${ocr.heif-convert:heif-convert}") String heifConvert) {
        this.heifConvert = heifConvert;
    }

    /** Convert HEIC/HEIF bytes to JPEG bytes; empty when conversion is not possible. */
    public Optional<byte[]> toJpeg(byte[] input) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("heic-");
            Path in = dir.resolve("in.heic");
            Path out = dir.resolve("out.jpg");
            Files.write(in, input);

            Process process = new ProcessBuilder(heifConvert, in.toString(), out.toString())
                    .redirectErrorStream(true)
                    .directory(dir.toFile())
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("heif-convert timed out");
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                log.warn("heif-convert exited with code {}", process.exitValue());
                return Optional.empty();
            }
            // heif-convert may write out.jpg or, for multi-image files, out-1.jpg — take the first jpg.
            Path jpg = firstJpeg(dir);
            if (jpg == null) {
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(jpg);
            return bytes.length > 0 ? Optional.of(bytes) : Optional.empty();
        } catch (IOException e) {
            log.warn("HEIC conversion failed", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            deleteRecursively(dir);
        }
    }

    private Path firstJpeg(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jpg")).findFirst().orElse(null);
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
