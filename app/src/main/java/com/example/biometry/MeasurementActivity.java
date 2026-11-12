package com.example.biometry;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.TextView;
import android.content.Intent;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MeasurementActivity extends AppCompatActivity {

    private TextView txtFileName;
    private Button btnSelectPdf;

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

                        Intent intent = new Intent(
                                MeasurementActivity.this,
                                ResultActivity.class
                        );

                        intent.putExtra("mode", mode);
                        startActivity(intent);

                    }
                }
        );

        btnSelectPdf.setOnClickListener(v ->
                pdfPicker.launch(new String[]{"application/pdf"})
        );

        btnBack.setOnClickListener(v -> finish());
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
    }
}
