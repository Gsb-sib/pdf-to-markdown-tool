package com.example.document.ocr;

import com.example.document.autoconfigure.DocumentProcessingProperties;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

public class Tess4jOcrService implements OcrService {
    private final DocumentProcessingProperties properties;
    private final ThreadLocal<Tesseract> tesseract;

    public Tess4jOcrService(DocumentProcessingProperties properties) {
        this.properties = properties;
        this.tesseract = ThreadLocal.withInitial(this::createTesseract);
    }

    @Override
    public String recognize(Path imagePath) {
        try {
            return tesseract.get().doOCR(imagePath.toFile());
        } catch (TesseractException ex) {
            throw new IllegalStateException("OCR failed for image: " + imagePath, ex);
        } catch (Error error) {
            throw new IllegalStateException(ocrNativeErrorMessage(), error);
        }
    }

    @Override
    public String recognize(BufferedImage image) {
        try {
            BufferedImage prepared = properties.getOcr().isPreprocess() ? preprocess(image) : image;
            return tesseract.get().doOCR(prepared);
        } catch (TesseractException ex) {
            throw new IllegalStateException("OCR failed for rendered image", ex);
        } catch (Error error) {
            throw new IllegalStateException(ocrNativeErrorMessage(), error);
        }
    }

    @Override
    public boolean isThreadSafe() {
        return true;
    }

    private Tesseract createTesseract() {
        Tesseract instance = new Tesseract();
        instance.setLanguage(properties.getOcr().getLanguage());
        if (properties.getOcr().getDatapath() != null && !properties.getOcr().getDatapath().isBlank()) {
            instance.setDatapath(properties.getOcr().getDatapath());
        }
        instance.setPageSegMode(properties.getOcr().getPageSegMode());
        instance.setOcrEngineMode(engineMode(properties.getOcr().getEngineMode()));
        instance.setVariable("user_defined_dpi", String.valueOf(properties.getOcr().getDpi()));
        instance.setVariable("preserve_interword_spaces", "1");
        return instance;
    }

    private int engineMode(String value) {
        if ("TESSERACT_ONLY".equalsIgnoreCase(value)) {
            return ITessAPI.TessOcrEngineMode.OEM_TESSERACT_ONLY;
        }
        if ("DEFAULT".equalsIgnoreCase(value)) {
            return ITessAPI.TessOcrEngineMode.OEM_DEFAULT;
        }
        return ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY;
    }

    private BufferedImage preprocess(BufferedImage source) {
        BufferedImage gray = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = gray.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();

        int threshold = Math.max(0, Math.min(255, properties.getOcr().getBinaryThreshold()));
        BufferedImage binary = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < gray.getHeight(); y++) {
            for (int x = 0; x < gray.getWidth(); x++) {
                int value = gray.getRaster().getSample(x, y, 0);
                binary.setRGB(x, y, value < threshold ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }
        return binary;
    }

    private String ocrNativeErrorMessage() {
        return "OCR native runtime failed. Make sure Tesseract OCR is installed and document.processing.ocr.datapath points to the tessdata directory containing chi_sim.traineddata and eng.traineddata.";
    }
}
