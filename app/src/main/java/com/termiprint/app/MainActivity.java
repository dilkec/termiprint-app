package com.termiprint.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private BluetoothPrinter bluetoothPrinter;

    private View viewStatusDot;
    private TextView tvPrinterStatus;
    private Button btnConnectBluetooth;

    private TextView tvDocumentName;
    private Button btnSelectFile;

    private RadioButton rb80mm;
    private TextView tvContrastLabel;
    private SeekBar sbContrast;
    private CheckBox cbFeedLines;
    private CheckBox cbCutPaper;

    private Button btnPrint;
    private ProgressBar progressBar;

    private ImageView ivThermalPreview;

    private Bitmap currentRawBitmap = null;
    private EscPosRasterizer.ProcessedResult currentProcessed = null;

    private final ActivityResultLauncher<String> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            this::handleSelectedUri
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bluetoothPrinter = new BluetoothPrinter();

        initViews();
        setupListeners();
        checkAndRequestPermissions();

        // Procesar si se abrió mediante el menú "COMPARTIR" de Android
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void initViews() {
        viewStatusDot = findViewById(R.id.viewStatusDot);
        tvPrinterStatus = findViewById(R.id.tvPrinterStatus);
        btnConnectBluetooth = findViewById(R.id.btnConnectBluetooth);

        tvDocumentName = findViewById(R.id.tvDocumentName);
        btnSelectFile = findViewById(R.id.btnSelectFile);

        rb80mm = findViewById(R.id.rb80mm);
        tvContrastLabel = findViewById(R.id.tvContrastLabel);
        sbContrast = findViewById(R.id.sbContrast);
        cbFeedLines = findViewById(R.id.cbFeedLines);
        cbCutPaper = findViewById(R.id.cbCutPaper);

        btnPrint = findViewById(R.id.btnPrint);
        progressBar = findViewById(R.id.progressBar);
        ivThermalPreview = findViewById(R.id.ivThermalPreview);
    }

    private void setupListeners() {
        btnConnectBluetooth.setOnClickListener(v -> {
            if (bluetoothPrinter.isConnected()) {
                bluetoothPrinter.disconnect();
                updateConnectionUi(false, null);
            } else {
                showDeviceChooserDialog();
            }
        });

        btnSelectFile.setOnClickListener(v -> filePickerLauncher.launch("*/*"));

        rb80mm.setOnCheckedChangeListener((btn, isChecked) -> reprocessCurrentBitmap());

        sbContrast.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvContrastLabel.setText("Intensidad de Contraste: " + progress);
                if (fromUser) {
                    reprocessCurrentBitmap();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        cbFeedLines.setOnCheckedChangeListener((btn, isChecked) -> reprocessCurrentBitmap());
        cbCutPaper.setOnCheckedChangeListener((btn, isChecked) -> reprocessCurrentBitmap());

        btnPrint.setOnClickListener(v -> executePrint());
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        String type = intent.getType();

        // 1. Recibido desde el menú "COMPARTIR" de Android (SEND)
        if (Intent.ACTION_SEND.equals(action) && type != null) {
            Uri streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (streamUri != null) {
                handleSelectedUri(streamUri);
                Toast.makeText(this, "Documento recibido para imprimir", Toast.LENGTH_SHORT).show();
            }
        }
        // 2. Abierto directamente desde el explorador de archivos (VIEW)
        else if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null) {
                handleSelectedUri(data);
            }
        }
    }

    private void handleSelectedUri(Uri uri) {
        if (uri == null) return;
        try {
            tvDocumentName.setText("Cargando documento...");
            currentRawBitmap = PdfProcessor.loadFromUri(this, uri);
            tvDocumentName.setText("Archivo listo para imprimir");
            reprocessCurrentBitmap();
        } catch (Exception e) {
            Toast.makeText(this, "Error al cargar archivo: " + e.getMessage(), Toast.LENGTH_LONG).show();
            tvDocumentName.setText("Error al cargar documento.");
        }
    }

    private void reprocessCurrentBitmap() {
        if (currentRawBitmap == null) {
            btnPrint.setEnabled(false);
            return;
        }

        int targetWidth = rb80mm.isChecked() ? 576 : 384;
        int threshold = sbContrast.getProgress();
        boolean feed = cbFeedLines.isChecked();
        boolean cut = cbCutPaper.isChecked();

        currentProcessed = EscPosRasterizer.process(currentRawBitmap, targetWidth, threshold, feed, cut);

        ivThermalPreview.setImageBitmap(currentProcessed.previewBitmap);
        btnPrint.setText(targetWidth == 576 ? R.string.btn_print_576 : R.string.btn_print_576);
        btnPrint.setEnabled(true);
    }

    @SuppressLint("MissingPermission")
    private void showDeviceChooserDialog() {
        List<BluetoothDevice> paired = bluetoothPrinter.getPairedPrinters();
        if (paired.isEmpty()) {
            Toast.makeText(this, "No hay impresoras Bluetooth vinculadas en tu tablet.", Toast.LENGTH_LONG).show();
            return;
        }

        String[] names = new String[paired.size()];
        for (int i = 0; i < paired.size(); i++) {
            BluetoothDevice dev = paired.get(i);
            names[i] = (dev.getName() != null ? dev.getName() : "Dispositivo") + "\n" + dev.getAddress();
        }

        new AlertDialog.Builder(this)
                .setTitle("Selecciona tu Impresora Bluetooth")
                .setItems(names, (dialog, which) -> {
                    BluetoothDevice selected = paired.get(which);
                    tvPrinterStatus.setText("Conectando...");
                    bluetoothPrinter.connect(selected, new BluetoothPrinter.ConnectionCallback() {
                        @Override
                        public void onConnected(String deviceName) {
                            updateConnectionUi(true, deviceName);
                            Toast.makeText(MainActivity.this, "Conectado a " + deviceName, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onDisconnected() {
                            updateConnectionUi(false, null);
                        }

                        @Override
                        public void onError(String error) {
                            updateConnectionUi(false, null);
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void updateConnectionUi(boolean connected, String devName) {
        if (connected) {
            viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.emerald_online));
            tvPrinterStatus.setText(getString(R.string.printer_status_connected, devName));
            btnConnectBluetooth.setText(R.string.btn_disconnect_bt);
        } else {
            viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.red_offline));
            tvPrinterStatus.setText(R.string.printer_status_disconnected);
            btnConnectBluetooth.setText(R.string.btn_connect_bt);
        }
    }

    private void executePrint() {
        if (currentProcessed == null || currentProcessed.escPosBytes == null) {
            Toast.makeText(this, "Carga un archivo primero.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothPrinter.isConnected()) {
            Toast.makeText(this, "Por favor conecta tu impresora primero.", Toast.LENGTH_SHORT).show();
            showDeviceChooserDialog();
            return;
        }

        btnPrint.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);

        bluetoothPrinter.print(currentProcessed.escPosBytes, new BluetoothPrinter.PrintProgressCallback() {
            @Override
            public void onProgress(int percent) {
                progressBar.setProgress(percent);
            }

            @Override
            public void onComplete() {
                btnPrint.setEnabled(true);
                progressBar.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "¡Impresión completada sin marcas de agua!", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                btnPrint.setEnabled(true);
                progressBar.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "Error: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, 101);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
