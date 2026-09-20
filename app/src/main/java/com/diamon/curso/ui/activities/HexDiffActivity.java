package com.diamon.curso.ui.activities;

import com.diamon.curso.R;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

public class HexDiffActivity extends AppCompatActivity {

    /** Dumps of 32MB flash parts are normal here, so the old 16MB cap was too low. */
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    private TextView tvDiffSummary, tvDiffStats, tvHashes;
    private RecyclerView recyclerDiff;
    private Button btnLoadFile1, btnLoadFile2;

    private byte[] dataA = null;
    private byte[] dataB = null;
    private String nameA = "";
    private String nameB = "";
    private String hashA = null;
    private String hashB = null;

    private final ActivityResultLauncher<Intent> file1Launcher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        try {
                            dataA = readUriToBytes(uri);
                            hashA = sha256(dataA);
                            nameA = getFileName(uri);
                            btnLoadFile1.setText(getString(R.string.str_file_a, nameA));
                            tryCompare();
                        } catch (Exception e) {
                            tvDiffSummary.setText(getString(R.string.str_err_load_file_a, e.getMessage()));
                        }
                    }
                }
            });

    private final ActivityResultLauncher<Intent> file2Launcher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        try {
                            dataB = readUriToBytes(uri);
                            hashB = sha256(dataB);
                            nameB = getFileName(uri);
                            btnLoadFile2.setText(getString(R.string.str_file_b, nameB));
                            tryCompare();
                        } catch (Exception e) {
                            tvDiffSummary.setText(getString(R.string.str_err_load_file_b, e.getMessage()));
                        }
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hex_diff);

        nameA = getString(R.string.str_archivo_a);
        nameB = getString(R.string.str_archivo_b);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.str_comparar_hex);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        com.diamon.curso.utils.WindowInsetsHelper.apply(
                this, findViewById(R.id.hexDiffRoot), 8);

        tvDiffSummary = findViewById(R.id.tvDiffSummary);
        tvDiffStats = findViewById(R.id.tvDiffStats);
        tvHashes = findViewById(R.id.tvHashes);
        recyclerDiff = findViewById(R.id.recyclerDiff);
        recyclerDiff.setLayoutManager(new LinearLayoutManager(this));
        btnLoadFile1 = findViewById(R.id.btnLoadFile1);
        btnLoadFile2 = findViewById(R.id.btnLoadFile2);

        // Pre-cargar bios.bin automáticamente si existe
        File biosFile = new File(getFilesDir(), "bios.bin");
        if (biosFile.exists()) {
            try {
                dataA = java.nio.file.Files.readAllBytes(biosFile.toPath());
                hashA = sha256(dataA);
                nameA = getString(R.string.str_bios_internal);
                btnLoadFile1.setText(getString(R.string.str_file_a, nameA));
                updateHashes();
            } catch (Exception ignored) {
            }
        }

        btnLoadFile1.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            file1Launcher.launch(intent);
        });

        btnLoadFile2.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            file2Launcher.launch(intent);
        });
    }

    private void tryCompare() {
        updateHashes();
        if (dataA == null || dataB == null) {
            tvDiffSummary.setText(R.string.str_load_both_files);
            return;
        }

        int maxLen = Math.max(dataA.length, dataB.length);
        int minLen = Math.min(dataA.length, dataB.length);
        int diffCount = 0;

        for (int i = 0; i < maxLen; i++) {
            byte a = i < dataA.length ? dataA[i] : (byte) 0xFF;
            byte b = i < dataB.length ? dataB[i] : (byte) 0xFF;
            if (a != b)
                diffCount++;
        }

        tvDiffSummary.setText(getString(R.string.str_compare_summary, nameA, dataA.length, nameB, dataB.length));

        tvDiffStats.setVisibility(View.VISIBLE);
        if (diffCount == 0) {
            tvDiffStats.setText(R.string.str_files_identical);
            tvDiffStats.setTextColor(0xFF4CAF50);
        } else {
            double pct = (diffCount * 100.0) / maxLen;
            tvDiffStats.setText(getString(R.string.str_files_different, diffCount, pct, maxLen));
            tvDiffStats.setTextColor(0xFFFF5252);
        }

        recyclerDiff.setAdapter(new DiffAdapter(dataA, dataB));
    }

    /** Lowercase hex SHA-256, matching what sha256sum prints on a desktop. */
    private static String sha256(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Shows the SHA-256 of whichever files are loaded. Useful on its own with a
     * single file, so a dump can be checked against sha256sum on a workstation.
     */
    private void updateHashes() {
        if (tvHashes == null) {
            return;
        }
        if (dataA == null && dataB == null) {
            tvHashes.setVisibility(View.GONE);
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (dataA != null) {
            sb.append("A  ").append(nameA).append('\n').append(hashA);
        }
        if (dataB != null) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("B  ").append(nameB).append('\n').append(hashB);
        }
        if (hashA != null && hashB != null) {
            sb.append("\n\n").append(getString(hashA.equals(hashB)
                    ? R.string.str_sha256_match : R.string.str_sha256_differ));
        }

        tvHashes.setText(sb.toString());
        tvHashes.setVisibility(View.VISIBLE);
    }

    private byte[] readUriToBytes(Uri uri) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null)
                throw new IllegalStateException(getString(R.string.str_err_open_file));
            
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (sizeIdx >= 0) {
                    long size = cursor.getLong(sizeIdx);
                    if (size > MAX_FILE_BYTES) {
                        cursor.close();
                        throw new IllegalArgumentException(getString(R.string.str_err_file_too_large_diff));
                    }
                }
                cursor.close();
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int nRead;
            long totalRead = 0;
            while ((nRead = is.read(buf)) != -1) {
                totalRead += nRead;
                if (totalRead > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException(getString(R.string.str_err_file_too_large_diff));
                }
                buffer.write(buf, 0, nRead);
            }
            return buffer.toByteArray();
        }
    }

    private String getFileName(Uri uri) {
        String name = getString(R.string.str_archivo_a);
        try {
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0)
                    name = cursor.getString(idx);
                cursor.close();
            }
        } catch (Exception ignored) {
        }
        return name;
    }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    // Adapter que muestra filas HEX con diferencias resaltadas en rojo
    static class DiffAdapter extends RecyclerView.Adapter<DiffAdapter.DiffViewHolder> {
        private final byte[] dataA;
        private final byte[] dataB;
        private final int maxLen;

        DiffAdapter(byte[] dataA, byte[] dataB) {
            this.dataA = dataA;
            this.dataB = dataB;
            this.maxLen = Math.max(dataA.length, dataB.length);
        }

        @NonNull
        @Override
        public DiffViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_hex_row, parent, false);
            return new DiffViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull DiffViewHolder holder, int position) {
            int rowStart = position * 16;
            int length = Math.min(16, maxLen - rowStart);

            StringBuilder hexBuilder = new StringBuilder();
            StringBuilder asciiBuilder = new StringBuilder();
            boolean rowHasDiff = false;

            for (int i = 0; i < 16; i++) {
                if (i < length) {
                    int idx = rowStart + i;
                    byte a = idx < dataA.length ? dataA[idx] : (byte) 0xFF;
                    byte b = idx < dataB.length ? dataB[idx] : (byte) 0xFF;
                    boolean isDiff = (a != b);
                    if (isDiff)
                        rowHasDiff = true;

                    // Muestra: A→B si difieren, solo A si iguales
                    if (isDiff) {
                        hexBuilder.append(String.format("%02X→%02X ", a & 0xFF, b & 0xFF));
                    } else {
                        hexBuilder.append(String.format("%02X     ", a & 0xFF));
                    }

                    if (a >= 32 && a <= 126) {
                        asciiBuilder.append((char) a);
                    } else {
                        asciiBuilder.append(".");
                    }
                } else {
                    hexBuilder.append("       ");
                    asciiBuilder.append(" ");
                }
            }

            holder.tvAddress.setText(String.format("%08X", rowStart));
            holder.tvHex.setText(hexBuilder.toString());
            holder.tvAscii.setText(asciiBuilder.toString());

            // Resaltar filas con diferencias
            if (rowHasDiff) {
                holder.tvHex.setTextColor(0xFFFF5252); // Rojo
                holder.tvAddress.setTextColor(0xFFFF9800); // Naranja
            } else {
                holder.tvHex.setTextColor(0xFFE0E0E0); // Normal
                holder.tvAddress.setTextColor(0xFF2196F3); // Azul normal
            }
        }

        @Override
        public int getItemCount() {
            return (int) Math.ceil((double) maxLen / 16.0);
        }

        static class DiffViewHolder extends RecyclerView.ViewHolder {
            TextView tvAddress, tvHex, tvAscii;

            DiffViewHolder(View itemView) {
                super(itemView);
                tvAddress = itemView.findViewById(R.id.tvAddress);
                tvHex = itemView.findViewById(R.id.tvHex);
                tvAscii = itemView.findViewById(R.id.tvAscii);
            }
        }
    }
}
