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
 * Local Tesseract OCR via Tess4J. Native Tesseract + language data are provided by the Docker image
 * (see the Dockerfile runtime stage). A fresh {@link Tesseract} instance is used per call because
 * Tess4J instances are not thread-safe.
 *
 * <p>Pipeline: decode the input (HEIC from iPhones is converted via {@link HeicConverter}), then
 * clean it via {@link ImagePreprocessor} (grayscale/deskew/normalize) before recognition. Uses
 * {@code rus+eng} by default because documents mix Cyrillic and Latin (emails, model numbers).
 */
@Service
public class OcrService {

    private final HeicConverter heicConverter;
    private final ImagePreprocessor preprocessor;
    private final String dataPath;
    private final String language;
    private final int pageSegMode;

    public OcrService(HeicConverter heicConverter,
                      ImagePreprocessor preprocessor,
                      @Value("${ocr.data-path:/usr/share/tesseract-ocr/5/tessdata}") String dataPath,
                      @Value("${ocr.language:rus+eng}") String language,
                      @Value("${ocr.page-seg-mode:3}") int pageSegMode) {
        this.heicConverter = heicConverter;
        this.preprocessor = preprocessor;
        this.dataPath = dataPath;
        this.language = language;
        this.pageSegMode = pageSegMode;
    }

    /**
     * Recognize text in an image. Returns "" when the bytes are not a readable image even after a
     * HEIC→JPEG attempt (e.g. a PDF — rendering PDFs to images is a later enhancement).
     */
    public String ocr(byte[] imageBytes) {
        byte[] raster = readableRaster(imageBytes);
        if (raster == null) {
            return "";
        }
        byte[] cleaned = preprocessor.process(raster).orElse(raster);
        BufferedImage image = readImage(cleaned);
        if (image == null) {
            image = readImage(raster);
        }
        if (image == null) {
            return "";
        }
        ITesseract tesseract = new Tesseract();
        tesseract.setDatapath(dataPath);
        tesseract.setLanguage(language);
        tesseract.setPageSegMode(pageSegMode);
        try {
            return tesseract.doOCR(image).trim();
        } catch (TesseractException e) {
            throw new IllegalStateException("Tesseract OCR failed", e);
        }
    }

    /** The input bytes if ImageIO can read them, otherwise a HEIC→JPEG conversion, otherwise null. */
    private byte[] readableRaster(byte[] input) {
        if (readImage(input) != null) {
            return input;
        }
        return heicConverter.toJpeg(input).filter(bytes -> readImage(bytes) != null).orElse(null);
    }

    private BufferedImage readImage(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }
}
