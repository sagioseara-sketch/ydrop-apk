package com.ydrop.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private LocalServer server;
    private static final int PORT = 7777;
    private static final int REQ_STORAGE = 100;
    private static final int REQ_MANAGE  = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // Ask for storage permission with explanation popup
        requestStorageWithDialog();
    }

    private void requestStorageWithDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ — needs MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                    .setTitle("Storage Permission Needed")
                    .setMessage("YDROP needs access to your storage to save downloaded videos and music to your Downloads folder.")
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .setCancelable(false)
                    .setPositiveButton("Allow", (dialog, which) -> {
                        Intent intent = new Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQ_MANAGE);
                    })
                    .setNegativeButton("Skip", (dialog, which) -> {
                        startServer();
                    })
                    .show();
            } else {
                startServer();
            }

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6–10 — runtime permission
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {

                new AlertDialog.Builder(this)
                    .setTitle("Storage Permission Needed")
                    .setMessage("YDROP needs storage access to save downloaded videos and music to your Downloads folder.")
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .setCancelable(false)
                    .setPositiveButton("Allow", (dialog, which) -> {
                        ActivityCompat.requestPermissions(this,
                            new String[]{
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            }, REQ_STORAGE);
                    })
                    .setNegativeButton("Skip", (dialog, which) -> {
                        startServer();
                    })
                    .show();
            } else {
                startServer();
            }

        } else {
            // Android 5 and below — permission granted at install
            startServer();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        // Whether granted or denied, start the server
        startServer();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // After returning from system settings, start the server
        startServer();
    }

    private void startServer() {
        if (server != null) return; // already started
        try {
            server = new LocalServer(this, PORT);
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
        webView.postDelayed(() -> webView.loadUrl("http://localhost:" + PORT), 800);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }
}
