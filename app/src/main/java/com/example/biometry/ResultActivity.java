package com.example.biometry;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class ResultActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        TextView txtResultIcon = findViewById(R.id.txtResultIcon);
        TextView txtResultTitle = findViewById(R.id.txtResultTitle);
        TextView txtResultDescription = findViewById(R.id.txtResultDescription);

        Button btnHome = findViewById(R.id.btnHome);

        String mode = getIntent().getStringExtra("mode");

        if ("register".equals(mode)) {
            txtResultIcon.setText("✓");
            txtResultTitle.setText("Wzorzec EKG zapisany");
            txtResultDescription.setText(
                    "Raport został wybrany jako wzorzec biometryczny. " +
                            "To demonstracja procesu rejestracji."
            );
        } else {
            txtResultIcon.setText("✓");
            txtResultTitle.setText("Tożsamość potwierdzona");
            txtResultDescription.setText(
                    "Przykładowy wynik weryfikacji na podstawie raportu EKG. " +
                            "Sygnał nie został rzeczywiście przeanalizowany."
            );
        }

        btnHome.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            startActivity(intent);
            finish();
        });
    }
}
