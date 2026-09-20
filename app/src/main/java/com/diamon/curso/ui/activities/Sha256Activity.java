package com.diamon.curso.ui.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.diamon.curso.R;
import com.diamon.curso.utils.WindowInsetsHelper;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hashes a set of dump files and says whether they agree.
 *
 * The workflow this exists for: read the same chip several times, export each
 * read with SAVE ROM under a different name, then add them all here. If every
 * hash matches you have a stable dump. If they do not, the reads are unreliable,
 * which with a CH341A usually means wiring, clip contact or an in-circuit chip
 * being driven by the board it is sitting on.
 *
 * Hashing streams the file rather than reading it into memory, so dump size does
 * not matter and there is no cap.
 */
public class Sha256Activity extends AppCompatActivity {

    private static class Entry {
        final String name;
        final String hash;
        final long size;

        Entry(String name, String hash, long size) {
            this.name = name;
            this.hash = hash;
            this.size = size;
        }
    }

    private TextView tvVerdict, tvList;
    private Button btnAdd, btnClear;

    private final List<Entry> entries = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                List<Uri> uris = new ArrayList<>();
                Intent data = result.getData();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        uris.add(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                if (!uris.isEmpty()) {
                    hashAll(uris);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sha256);

        WindowInsetsHelper.apply(this, findViewById(R.id.sha256Root), 16);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.str_sha256_screen_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvVerdict = findViewById(R.id.tvSha256Verdict);
        tvList = findViewById(R.id.tvSha256List);
        btnAdd = findViewById(R.id.btnSha256Add);
        btnClear = findViewById(R.id.btnSha256Clear);

        btnAdd.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            picker.launch(intent);
        });

        btnClear.setOnClickListener(v -> {
            entries.clear();
            render();
        });

        render();
    }

    private void hashAll(final List<Uri> uris) {
        btnAdd.setEnabled(false);
        tvVerdict.setText(R.string.str_sha256_hashing);
        tvVerdict.setTextColor(0xFFB0BEC5);

        executor.execute(() -> {
            final List<Entry> added = new ArrayList<>();
            for (Uri uri : uris) {
                String name = queryName(uri);
                try {
                    long[] size = new long[1];
                    String hash = sha256Stream(uri, size);
                    added.add(new Entry(name, hash, size[0]));
                } catch (Exception e) {
                    added.add(new Entry(name, getString(R.string.str_sha256_unreadable), -1));
                }
            }
            main.post(() -> {
                entries.addAll(added);
                btnAdd.setEnabled(true);
                render();
            });
        });
    }

    /** Streams the file through SHA-256 so size does not matter. */
    private String sha256Stream(Uri uri, long[] sizeOut) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IllegalStateException("cannot open");
            }
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) != -1) {
                md.update(buf, 0, n);
                total += n;
            }
        }
        sizeOut[0] = total;
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private String queryName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (android.database.Cursor c =
                     getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return name == null ? "?" : name;
    }

    private void render() {
        btnClear.setEnabled(!entries.isEmpty());

        if (entries.isEmpty()) {
            tvVerdict.setText(R.string.str_sha256_empty);
            tvVerdict.setTextColor(0xFFB0BEC5);
            tvList.setText("");
            return;
        }

        // Group by hash, preserving the order files were added.
        Map<String, Integer> groups = new LinkedHashMap<>();
        boolean anyUnreadable = false;
        for (Entry e : entries) {
            if (e.size < 0) {
                anyUnreadable = true;
                continue;
            }
            Integer n = groups.get(e.hash);
            groups.put(e.hash, n == null ? 1 : n + 1);
        }

        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.name).append('\n');
            if (e.size >= 0) {
                sb.append(e.hash).append('\n');
                sb.append(e.size / 1024).append(" kB\n\n");
            } else {
                sb.append(getString(R.string.str_sha256_unreadable)).append("\n\n");
            }
        }
        tvList.setText(sb.toString().trim());

        int readable = entries.size() - (anyUnreadable ? countUnreadable() : 0);
        if (groups.size() == 1 && readable > 1) {
            tvVerdict.setText(getString(R.string.str_sha256_all_match, readable));
            tvVerdict.setTextColor(0xFF4CAF50);
        } else if (groups.size() > 1) {
            tvVerdict.setText(getString(R.string.str_sha256_groups, readable, groups.size()));
            tvVerdict.setTextColor(0xFFFF5252);
        } else {
            tvVerdict.setText(R.string.str_sha256_need_more);
            tvVerdict.setTextColor(0xFFB0BEC5);
        }
    }

    private int countUnreadable() {
        int n = 0;
        for (Entry e : entries) {
            if (e.size < 0) {
                n++;
            }
        }
        return n;
    }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
