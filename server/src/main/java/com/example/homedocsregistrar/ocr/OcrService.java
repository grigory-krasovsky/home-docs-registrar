package com.example.homedocsregistrar.ocr;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Local Tesseract OCR via Tess4J. Native Tesseract + the {@code rus} language data are provided by
 * the Docker image (see the Dockerfile runtime stage). A fresh {@link Tesseract} instance is used
 * per call because Tess4J instances are not thread-safe.
 *
 * <p>Inputs ImageIO can't read (notably iPhone HEIC) are first converted to JPEG via
 * {@link HeicConverter} and retried.
 */
@Service
public class OcrService {

    private final HeicConverter heicConverter;
    private final String dataPath;
    private final String language;

    public OcrService(HeicConverter heicConverter,
                      @Value("${ocr.data-path:/usr/share/tesseract-ocr/5/tessdata}") String dataPath,
                      @Value("${ocr.language:rus}") String language) {
        this.heicConverter = heicConverter;
        this.dataPath = dataPath;
        this.language = language;
    }

    /**
     * Recognize text in an image. Returns "" when the bytes are not a readable image even after a
     * HEIC→JPEG attempt (e.g. a PDF — rendering PDFs to images is a later enhancement).
     */
    public String ocr(byte[] imageBytes) {
        BufferedImage image = readImage(imageBytes);
        if (image == null) {
            image = heicConverter.toJpeg(imageBytes).map(this::readImage).orElse(null);
        }
        if (image == null) {
            return "";
        }
        ITesseract tesseract = new Tesseract();
        tesseract.setDatapath(dataPath);
        tesseract.setLanguage(language);
        try {
            return tesseract.doOCR(image).trim();
        } catch (TesseractException e) {
            throw new IllegalStateException("Tesseract OCR failed", e);
        }
    }

    private BufferedImage readImage(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }
}
