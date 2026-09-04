package com.termiprint.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Conversor de Bitmaps a comandos binarios ESC/POS (GS v 0)
 * Incluye recorte inteligente de espacio en blanco inferior e impresión opcional de fecha/hora.
 */
public class EscPosRasterizer {

    public static class ProcessedResult {
        public Bitmap previewBitmap;
        public byte[] escPosBytes;
    }

    /**
     * Procesa un Bitmap fuente al ancho exacto de la impresora térmica.
     * @param srcBitmap Imagen original
     * @param targetWidth 576 o 384 puntos
     * @param threshold Umbral de luminosidad (50 - 220)
     * @param trimBottom Recortar espacio en blanco sobrante inferior
     * @param printTimestamp Imprimir pie de fecha y hora al final
     * @param advanceMargin Cortar a medio centímetro
     * @param extraFeedLines Líneas adicionales a avanzar al terminar
     */
    public static ProcessedResult process(Bitmap srcBitmap, int targetWidth, int threshold,
                                          boolean trimBottom, boolean printTimestamp, boolean advanceMargin,
                                          int extraFeedLines) {
        int srcWidth = srcBitmap.getWidth();
        int srcHeight = srcBitmap.getHeight();

        float scale = (float) targetWidth / srcWidth;
        int targetHeight = Math.max(1, Math.round(srcHeight * scale));

        // 1. Escalar mapa de bits original
        Bitmap scaled = Bitmap.createScaledBitmap(srcBitmap, targetWidth, targetHeight, true);
        int[] pixels = new int[targetWidth * targetHeight];
        scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight);

        // 2. Convertir a escala de grises
        float[] gray = new float[targetWidth * targetHeight];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b;
        }

        // 3. Difusión de error Floyd-Steinberg
        byte[] bits = new byte[targetWidth * targetHeight];
        int lastContentRow = 0;

        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int idx = y * targetWidth + x;
                float oldVal = gray[idx];
                float newVal = oldVal < threshold ? 0 : 255;
                byte bitVal = (byte) (newVal == 0 ? 1 : 0);
                bits[idx] = bitVal;

                if (bitVal == 1) {
                    lastContentRow = y;
                }

                float err = oldVal - newVal;
                if (x + 1 < targetWidth) gray[idx + 1] += (err * 7) / 16;
                if (x - 1 >= 0 && y + 1 < targetHeight) gray[(y + 1) * targetWidth + (x - 1)] += (err * 3) / 16;
                if (y + 1 < targetHeight) gray[(y + 1) * targetWidth + x] += (err * 5) / 16;
                if (x + 1 < targetWidth && y + 1 < targetHeight) gray[(y + 1) * targetWidth + (x + 1)] += (err * 1) / 16;
            }
        }

        // 4. Recorte de espacio en blanco inferior
        int effectiveHeight = targetHeight;
        if (trimBottom && lastContentRow > 0) {
            // Guardar solo hasta la última fila con tinta + pequeño margen de 15 puntos (~2mm)
            effectiveHeight = Math.min(targetHeight, lastContentRow + 15);
        }

        // 5. Agregar Fecha y Hora si está activado
        int timestampExtraHeight = printTimestamp ? 40 : 0;
        int finalHeight = effectiveHeight + timestampExtraHeight;

        // Crear mapa de bits final para previsualización
        Bitmap finalBitmap = Bitmap.createBitmap(targetWidth, finalHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(finalBitmap);
        canvas.drawColor(Color.WHITE);

        // Volcar contenido procesado
        int[] outPixels = new int[targetWidth * effectiveHeight];
        for (int i = 0; i < outPixels.length; i++) {
            outPixels[i] = (bits[i] == 1) ? Color.BLACK : Color.WHITE;
        }
        finalBitmap.setPixels(outPixels, 0, targetWidth, 0, 0, targetWidth, effectiveHeight);

        // Dibujar fecha y hora al final
        byte[] finalBits = new byte[targetWidth * finalHeight];
        System.arraycopy(bits, 0, finalBits, 0, targetWidth * effectiveHeight);

        if (printTimestamp) {
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.BLACK);
            textPaint.setTextSize(20f);
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            textPaint.setTextAlign(Paint.Align.CENTER);

            String timeStr = "Impreso: " + new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date());
            float textY = effectiveHeight + 26f;
            canvas.drawText(timeStr, targetWidth / 2f, textY, textPaint);

            // Rasterizar la sección del texto a los bits finales
            int[] textPixels = new int[targetWidth * timestampExtraHeight];
            finalBitmap.getPixels(textPixels, 0, targetWidth, 0, effectiveHeight, targetWidth, timestampExtraHeight);
            for (int i = 0; i < textPixels.length; i++) {
                int p = textPixels[i];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                float luminance = 0.299f * r + 0.587f * g + 0.114f * b;
                finalBits[targetWidth * effectiveHeight + i] = (byte) (luminance < 160 ? 1 : 0);
            }
        }

        // 6. Generar comandos ESC/POS
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            // Inicializar impresora: ESC @
            baos.write(new byte[]{0x1B, 0x40});
            // Alinear al centro o izquierda: ESC a 0
            baos.write(new byte[]{0x1B, 0x61, 0x00});

            int widthBytes = (targetWidth + 7) / 8;
            int bandHeight = 128;

            int yOffset = 0;
            while (yOffset < finalHeight) {
                int currentBandHeight = Math.min(bandHeight, finalHeight - yOffset);

                baos.write(0x1D); // GS
                baos.write(0x76); // v
                baos.write(0x30); // 0
                baos.write(0x00); // m
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
                                if (finalBits[actualY * targetWidth + actualX] == 1) {
                                    byteVal |= (0x80 >> bit);
                                }
                            }
                        }
                        baos.write(byteVal);
                    }
                }
                yOffset += currentBandHeight;
            }

            // Avance mínimo: cortar exactamente a medio centímetro (~40 puntos / 1 línea)
            // ESC J 36 (avanza exactamente 36 puntos = aprox 4.5 mm)
            if (advanceMargin) {
                baos.write(new byte[]{0x1B, 0x4A, 0x24});
            }

            // Avance adicional de líneas configurables por el usuario (ESC d n)
            if (extraFeedLines > 0) {
                baos.write(new byte[]{0x1B, 0x64, (byte) Math.min(extraFeedLines, 15)});
            }

        } catch (IOException ignored) {}

        ProcessedResult result = new ProcessedResult();
        result.previewBitmap = finalBitmap;
        result.escPosBytes = baos.toByteArray();
        return result;
    }
}
