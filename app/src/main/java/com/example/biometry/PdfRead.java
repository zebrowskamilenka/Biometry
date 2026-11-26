package com.example.biometry;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

public class PdfRead {
    private static final String PREFS_NAME = "BiometryPrefs";
    private static final String KEY_TEMPLATE = "ecg_template";

    // Uruchamia Pythona bezpiecznie z dowolnej aktywności
    private static PyObject getEngine(Context context) {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context.getApplicationContext()));
        }
        return Python.getInstance().getModule("ecg_engine");
    }

    // Zamienia pierwszą stronę wybranego pliku PDF na obrazek PNG w cache
    private static String renderPdfPageToPng(Context context, Uri pdfUri, String outputFileName) throws Exception {
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(pdfUri, "r");
        if (pfd == null) {
            throw new Exception("Nie można otworzyć pliku PDF");
        }

        PdfRenderer renderer = new PdfRenderer(pfd);
        PdfRenderer.Page page = renderer.openPage(0);

        int width = page.getWidth() * 2;
        int height = page.getHeight() * 2;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

        page.close();
        renderer.close();
        pfd.close();

        File outFile = new File(context.getCacheDir(), outputFileName);
        try (FileOutputStream out = new FileOutputStream(outFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return outFile.getAbsolutePath();
    }

    // 1. Wywołujesz w aktywności od WZORCA
    public static boolean saveReferenceFromPdf(Context context, Uri pdfUri) {
        try {
            String pngPath = renderPdfPageToPng(context, pdfUri, "reference_ecg.png");
            PyObject engine = getEngine(context);
            String resultJsonStr = engine.callAttr("process_reference", pngPath).toString();
            android.util.Log.e("ECG_DEBUG", resultJsonStr);

            JSONObject res = new JSONObject(resultJsonStr);
            if ("success".equals(res.getString("status"))) {
                String templateArrayStr = res.getJSONArray("template").toString();
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_TEMPLATE, templateArrayStr).apply();
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    // 2. Wywołujesz w aktywności od WERYFIKACJI
    public static boolean verifyFromPdf(Context context, Uri pdfUri) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String savedTemplate = prefs.getString(KEY_TEMPLATE, null);
            if (savedTemplate == null) {
                return false; // Brak zapisanego wzorca
            }

            String pngPath = renderPdfPageToPng(context, pdfUri, "verify_ecg.png");
            PyObject engine = getEngine(context);
            String resultJsonStr = engine.callAttr("verify_ecg", pngPath, savedTemplate).toString();

            JSONObject res = new JSONObject(resultJsonStr);
            return res.optBoolean("match", false);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}
