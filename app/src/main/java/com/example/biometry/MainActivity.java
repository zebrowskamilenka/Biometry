package com.example.biometry;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnRegister = findViewById(R.id.btnRegister);
        Button btnVerify = findViewById(R.id.btnVerify);

        btnRegister.setOnClickListener(v -> {
            Intent intent = new Intent(this, MeasurementActivity.class);
            intent.putExtra("mode", "register");
            startActivity(intent);
        });

        btnVerify.setOnClickListener(v -> {
            Intent intent = new Intent(this, MeasurementActivity.class);
            intent.putExtra("mode", "verify");
            startActivity(intent);
        });
    }
}
