package com.example.biometry;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

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
        boolean success = getIntent().getBooleanExtra("success", false);

        int resultColor = ContextCompat.getColor(
                this,
                success ? R.color.success : R.color.error
        );


        if ("register".equals(mode)) {
            if(success){
                txtResultIcon.setText("✓");
            txtResultTitle.setText("Wzorzec EKG zapisany");
            txtResultDescription.setText(
                    "Raport został wybrany jako wzorzec biometryczny. " +
                            "Został poprawnie zapisany."
            );}
            else{
                txtResultIcon.setText("x");
                txtResultTitle.setText("Wzorzec EKG nie został zapisany");
                txtResultDescription.setText(
                        "Nie udało się wyodrębnić uderzeń serca z pliku PDF. "
                );
            }
        } else {
            if(success){
            txtResultIcon.setText("✓");
            txtResultTitle.setText("Tożsamość potwierdzona");
            txtResultDescription.setText(
                    "Przykładowy wynik weryfikacji na podstawie raportu EKG. " +
                            "Sygnał został poprawnie  zweryfikowany."
            );}
            else{
                txtResultIcon.setText("x");
                txtResultTitle.setText("Tożsamość nie potwierdzona");
                txtResultDescription.setText(
                        "Odmowa dostępu (weryfikacja NEGATYWNA lub brak wzorca) "
                );
            }
        }

        // Ustawienie koloru wyniku weryfikacji
        txtResultIcon.setTextColor(resultColor);
        txtResultTitle.setTextColor(resultColor);
        txtResultDescription.setTextColor(resultColor);

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
