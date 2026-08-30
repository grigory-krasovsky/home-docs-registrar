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
 */
@Service
public class OcrService {

    private final String dataPath;
    private final String language;

    public OcrService(@Value("${ocr.data-path:/usr/share/tesseract-ocr/5/tessdata}") String dataPath,
                      @Value("${ocr.language:rus}") String language) {
        this.dataPath = dataPath;
        this.language = language;
    }

    /**
     * Recognize text in a raster image. Returns "" when the bytes are not a readable raster image
     * (e.g. a PDF — rendering PDFs to images is a later enhancement).
     */
    public String ocr(byte[] imageBytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read image bytes for OCR", e);
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
}
