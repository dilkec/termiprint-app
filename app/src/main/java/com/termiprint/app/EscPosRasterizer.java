package com.termiprint.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Conversor de Bitmaps a comandos binarios ESC/POS (GS v 0) con difusión Floyd-Steinberg.
 */
public class EscPosRasterizer {

    public static class ProcessedResult {
        public Bitmap previewBitmap;
        public byte[] escPosBytes;
    }

    /**
     * Procesa un Bitmap fuente al ancho exacto de la impresora térmica con algoritmo Floyd-Steinberg.
     * @param srcBitmap Imagen original
     * @param targetWidth 576 o 384 puntos
     * @param threshold Umbral de luminosidad (típicamente 130)
     * @param feedLines Si avanzar papel al final
     * @param cutPaper Si enviar comando de corte
     */
    public static ProcessedResult process(Bitmap srcBitmap, int targetWidth, int threshold, boolean feedLines, boolean cutPaper) {
        int srcWidth = srcBitmap.getWidth();
        int srcHeight = srcBitmap.getHeight();

        float scale = (float) targetWidth / srcWidth;
        int targetHeight = Math.max(1, Math.round(srcHeight * scale));

        // Escalar mapa de bits
        Bitmap scaled = Bitmap.createScaledBitmap(srcBitmap, targetWidth, targetHeight, true);
        int[] pixels = new int[targetWidth * targetHeight];
        scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight);

        // Convertir a escala de grises
        float[] gray = new float[targetWidth * targetHeight];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b;
        }

        // Difusión de error Floyd-Steinberg
        byte[] bits = new byte[targetWidth * targetHeight];
        int[] outPixels = new int[targetWidth * targetHeight];

        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int idx = y * targetWidth + x;
                float oldVal = gray[idx];
                float newVal = oldVal < threshold ? 0 : 255;
                bits[idx] = (byte) (newVal == 0 ? 1 : 0);
                outPixels[idx] = newVal == 0 ? Color.BLACK : Color.WHITE;

                float err = oldVal - newVal;
                if (x + 1 < targetWidth) gray[idx + 1] += (err * 7) / 16;
                if (x - 1 >= 0 && y + 1 < targetHeight) gray[(y + 1) * targetWidth + (x - 1)] += (err * 3) / 16;
                if (y + 1 < targetHeight) gray[(y + 1) * targetWidth + x] += (err * 5) / 16;
                if (x + 1 < targetWidth && y + 1 < targetHeight) gray[(y + 1) * targetWidth + (x + 1)] += (err * 1) / 16;
            }
        }

        // Crear Bitmap monocromático para la previsualización visual
        Bitmap preview = Bitmap.createBitmap(outPixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888);

        // Generar secuencia de bytes ESC/POS
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            // 1. Resetear impresora: ESC @
            baos.write(new byte[]{0x1B, 0x40});
            // 2. Alinear izquierda: ESC a 0
            baos.write(new byte[]{0x1B, 0x61, 0x00});

            int widthBytes = (targetWidth + 7) / 8;
            int bandHeight = 128; // Bandas seguras de 128 líneas de alto

            int yOffset = 0;
            while (yOffset < targetHeight) {
                int currentBandHeight = Math.min(bandHeight, targetHeight - yOffset);

                // GS v 0 m xL xH yL yH
                baos.write(0x1D);
                baos.write(0x76);
                baos.write(0x30);
                baos.write(0x00);
                baos.write(widthBytes & 0xFF);
                baos.write((widthBytes >> 8) & 0xFF);
                baos.write(currentBandHeight & 0xFF);
                baos.write((currentBandHeight >> 8) & 0xFF);

                for (int y = 0; y < currentBandHeight; y++) {
                    int actualY = yOffset + y;
                    for (int xByte = 0; xByte < widthBytes; xByte++) {
                        int byteVal = 0;
                        for (int bit = 0; bit < 8; bit++) {
                            int actualX = xByte * 8 + bit;
                            if (actualX < targetWidth) {
                                if (bits[actualY * targetWidth + actualX] == 1) {
                                    byteVal |= (0x80 >> bit);
                                }
                            }
                        }
                        baos.write(byteVal);
                    }
                }
                yOffset += currentBandHeight;
            }

            // 4. Alimentar papel: ESC d 4
            if (feedLines) {
                baos.write(new byte[]{0x1B, 0x64, 0x04});
            }

            // 5. Corte parcial de papel: GS V 66 0
            if (cutPaper) {
                baos.write(new byte[]{0x1D, 0x56, 0x42, 0x00});
            }
        } catch (IOException ignored) {}

        ProcessedResult result = new ProcessedResult();
        result.previewBitmap = preview;
        result.escPosBytes = baos.toByteArray();
        return result;
    }
}
