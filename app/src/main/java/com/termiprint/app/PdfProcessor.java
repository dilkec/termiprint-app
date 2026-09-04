package com.termiprint.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Procesador de archivos PDF e imágenes a Bitmaps nativos de Android.
 */
public class PdfProcessor {

    public static Bitmap loadFromUri(Context context, Uri uri) throws Exception {
        String mimeType = context.getContentResolver().getType(uri);
        boolean isPdf = (mimeType != null && mimeType.contains("pdf")) ||
                        (uri.toString().toLowerCase().endsWith(".pdf"));

        if (isPdf) {
            return renderPdfPage(context, uri, 0);
        } else {
            return loadImage(context, uri);
        }
    }

    private static Bitmap loadImage(Context context, Uri uri) throws Exception {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (bmp == null) {
                throw new Exception("No se pudo decodificar la imagen.");
            }
            return bmp;
        }
    }

    private static Bitmap renderPdfPage(Context context, Uri uri, int pageIndex) throws Exception {
        // Copiar a archivo temporal para obtener ParcelFileDescriptor
        File tempFile = new File(context.getCacheDir(), "temp_document.pdf");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tempFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }

        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(pfd)) {

            if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
                pageIndex = 0;
            }

            try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                // Escala 2.0x para nitidez de texto fino
                int width = page.getWidth() * 2;
                int height = page.getHeight() * 2;

                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.WHITE); // Fondo blanco limpio

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                return bitmap;
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
}
