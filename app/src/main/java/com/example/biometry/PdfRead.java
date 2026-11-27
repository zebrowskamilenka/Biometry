package com.example.biometry;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

public class PdfRead {

    private static final String PREFS_NAME = "BiometryPrefs";
    private static final String KEY_TEMPLATE = "ecg_template";

    private static PyObject getEngine(Context context) {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context.getApplicationContext()));
        }
        return Python.getInstance().getModule("ecg_engine");
    }

    private static String renderPdfPageToPng(Context context, Uri pdfUri, String prefix) throws Exception {
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


        File outFile = new File(context.getCacheDir(), prefix + "_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(outFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return outFile.getAbsolutePath();
    }

    public static boolean saveReferenceFromPdf(Context context, Uri pdfUri) {
        File pngFile = null;
        try {
            String pngPath = renderPdfPageToPng(context, pdfUri, "ref");
            pngFile = new File(pngPath);

            PyObject engine = getEngine(context);
            String resultJsonStr = engine.callAttr("process_reference", pngPath).toString();

            JSONObject res = new JSONObject(resultJsonStr);
            if ("success".equals(res.getString("status"))) {
                String templateArrayStr = res.getJSONArray("template").toString();
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                return prefs.edit().putString(KEY_TEMPLATE, templateArrayStr).commit();
            }
        } catch (Exception e) {
            Log.e("ECG_DEBUG", "REGISTER ERROR", e);
        } finally {
            if (pngFile != null && pngFile.exists()) {
                pngFile.delete();
            }
        }
        return false;
    }

    public static boolean verifyFromPdf(Context context, Uri pdfUri) {
        File pngFile = null;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String savedTemplate = prefs.getString(KEY_TEMPLATE, null);
            if (savedTemplate == null) {
                Log.e("ECG_DEBUG", "VERIFY ERROR: Brak zapisanego wzorca w SharedPreferences");
                return false;
            }

            String pngPath = renderPdfPageToPng(context, pdfUri, "ver");
            pngFile = new File(pngPath);

            PyObject engine = getEngine(context);
            String resultJsonStr = engine.callAttr("verify_ecg", pngPath, savedTemplate).toString();
            Log.e("ECG_DEBUG", "VERIFY RESPONSE: " + resultJsonStr);

            JSONObject res = new JSONObject(resultJsonStr);
            return res.optBoolean("match", false);
        } catch (Exception e) {
            Log.e("ECG_DEBUG", "VERIFY ERROR", e);
        } finally {
            if (pngFile != null && pngFile.exists()) {
                pngFile.delete();
            }
        }
        return false;
    }
}