package com.example.biometry;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MeasurementActivity extends AppCompatActivity {

    private TextView txtFileName;
    private Button btnSelectPdf;
    private ImageView imgPdfPreview;
    private TextView txtPreviewPlaceholder;
    private TextView txtAnalysisStatus;
    private ProgressBar analysisProgress;

    private ActivityResultLauncher<String[]> pdfPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_measurement);

        TextView btnBack = findViewById(R.id.btnBack);
        TextView txtTitle = findViewById(R.id.txtTitle);
        TextView txtDescription = findViewById(R.id.txtDescription);

        txtFileName = findViewById(R.id.txtFileName);
        btnSelectPdf = findViewById(R.id.btnSelectPdf);
        imgPdfPreview = findViewById(R.id.imgPdfPreview);
        txtPreviewPlaceholder = findViewById(R.id.txtPreviewPlaceholder);
        txtAnalysisStatus = findViewById(R.id.txtAnalysisStatus);
        analysisProgress = findViewById(R.id.analysisProgress);

        String mode = getIntent().getStringExtra("mode");

        if ("register".equals(mode)) {
            txtTitle.setText("Rejestracja wzorca EKG");
            txtDescription.setText(
                    "Wybierz pierwszy raport EKG, który zostanie zapisany jako wzorzec biometryczny."
            );
            btnSelectPdf.setText("Wybierz wzorzec PDF");
        } else {
            txtTitle.setText("Weryfikacja EKG");
            txtDescription.setText(
                    "Wybierz nowy raport EKG, który zostanie porównany z zapisanym wzorcem."
            );
            btnSelectPdf.setText("Wybierz pomiar PDF");
        }

        pdfPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        showSelectedFile(uri);
                        processPdfAndNavigate(uri, mode);

                    }
                }
        );

        btnSelectPdf.setOnClickListener(v ->
                pdfPicker.launch(new String[]{"application/pdf"})
        );

        btnBack.setOnClickListener(v -> finish());
    }

    private void processPdfAndNavigate(Uri uri, String mode) {
        // Blokujemy przycisk na czas analizy w Pythonie i informujemy użytkownika
        btnSelectPdf.setEnabled(false);
        btnSelectPdf.setText("Przetwarzanie sygnału EKG...");
        analysisProgress.setVisibility(View.VISIBLE);

        txtAnalysisStatus.setText(
                "Rozpoczynanie analizy EKG..."
        );

        new Thread(() -> {
            boolean success;
            if ("register".equals(mode)) {
                success = PdfRead.saveReferenceFromPdf(this, uri);
            } else {
                success = PdfRead.verifyFromPdf(this, uri);
            }

            // Wracamy na wątek główny UI, aby przejść do ResultActivity
            runOnUiThread(() -> {

                btnSelectPdf.setEnabled(true);
                btnSelectPdf.setText("Wybierz inny plik PDF");

                analysisProgress.setVisibility(View.GONE);

                if (success) {
                    txtAnalysisStatus.setText(
                            "Analiza zakończona pomyślnie."
                    );
                } else {
                    txtAnalysisStatus.setText(
                            "Analiza zakończona. Sprawdź wynik."
                    );
                }

                Intent intent = new Intent(
                        MeasurementActivity.this,
                        ResultActivity.class
                );

                intent.putExtra("mode", mode);
                intent.putExtra("success", success);

                startActivity(intent);
            });
        }).start();
    }
    private void showSelectedFile(Uri uri) {
        String fileName = "Wybrano plik PDF";

        Cursor cursor = getContentResolver().query(
                uri,
                null,
                null,
                null,
                null
        );

        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(
                            OpenableColumns.DISPLAY_NAME
                    );

                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex);
                    }
                }
            } finally {
                cursor.close();
            }
        }

        txtFileName.setText("Wybrany plik: " + fileName);
        btnSelectPdf.setText("Wybierz inny plik PDF");
        renderPdfPreview(uri);
    }

    private void renderPdfPreview(Uri uri) {

        new Thread(() -> {
            Bitmap bitmap = null;

            try {
                ParcelFileDescriptor fileDescriptor =
                        getContentResolver().openFileDescriptor(uri, "r");

                if (fileDescriptor != null) {
                    PdfRenderer renderer =
                            new PdfRenderer(fileDescriptor);

                    if (renderer.getPageCount() > 0) {
                        PdfRenderer.Page page = renderer.openPage(0);

                        bitmap = Bitmap.createBitmap(
                                page.getWidth(),
                                page.getHeight(),
                                Bitmap.Config.ARGB_8888
                        );

                        page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        );

                        page.close();
                    }

                    renderer.close();
                    fileDescriptor.close();
                }

            } catch (Exception e) {
                e.printStackTrace();
            }

            Bitmap finalBitmap = bitmap;

            runOnUiThread(() -> {
                if (finalBitmap != null) {
                    imgPdfPreview.setImageBitmap(finalBitmap);
                    imgPdfPreview.setVisibility(View.VISIBLE);
                    txtPreviewPlaceholder.setVisibility(View.GONE);
                } else {
                    txtPreviewPlaceholder.setText(
                            "Nie udało się wyświetlić podglądu PDF"
                    );
                    txtPreviewPlaceholder.setVisibility(View.VISIBLE);
                }
            });

        }).start();
    }

}
