package com.shise.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.SwipeRefreshLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final String REPO_RAW = "https://raw.githubusercontent.com/soyuangfifi/super-sticky-note/main";
    static final String LATEST_JSON = REPO_RAW + "/latest.json";
    static final String WEB_ASSET = "file:///android_res/";
    static final String REMOTE_HTML = REPO_RAW + "/shise.html";

    WebView webView;
    FrameLayout root;
    View progress;
    boolean hasRemoteUpdate = false;
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setSupportZoom(false);
        webView.setBackgroundColor(0xFFF4F0E8);

        SwipeRefreshLayout swr = new SwipeRefreshLayout(this);
        swr.addView(webView, new SwipeRefreshLayout.LayoutParams(-1, -1));
        swr.setColorSchemeColors(0xFFC41A1A);
        swr.setOnRefreshListener(() -> refreshFromRemote());
        root.addView(swr, new FrameLayout.LayoutParams(-1, -1));

        progress = new View(this);
        progress.setBackgroundColor(0xFFC41A1A);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(60, 4);
        pl.gravity = Gravity.CENTER;
        progress.setLayoutParams(pl);
        progress.setVisibility(View.GONE);
        root.addView(progress);
        setContentView(root);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String s = u.toString();
                if (s.startsWith("file:///android_res/") || s.startsWith("data:")
                        || s.contains("soyuangfifi.github.io")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.getLayoutParams().width = Math.round(60 * newProgress / 100f);
                progress.requestLayout();
                if (newProgress >= 100) progress.setVisibility(View.GONE);
            }
        });

        // 秒开内置页
        webView.loadUrl(WEB_ASSET);

        // 后台拉远程最新 shise.html → 有更新显示"内容已更新"条
        io.execute(() -> {
            try {
                byte[] html = httpGet(REMOTE_HTML);
                if (html != null) {
                    main.post(() -> showUpdateBar());
                }
            } catch (Exception ignored) {
            }
        });

        // 后台检查 OTA：latest.json 版本 > 当前 → 提示下载新 APK
        io.execute(() -> {
            try {
                byte[] js = httpGet(LATEST_JSON);
                if (js == null) return;
                JSONObject o = new JSONObject(new String(js, "UTF-8"));
                main.post(() -> checkOta(
                        o.optString("version", ""),
                        o.optString("apkUrl", ""),
                        o.optString("sha256", "")));
            } catch (Exception ignored) {
            }
        });
    }

    void showUpdateBar() {
        hasRemoteUpdate = true;
        if (root.findViewWithTag("update") != null) return;
        android.widget.Button b = new android.widget.Button(this);
        b.setTag("update");
        b.setText("内容已更新 · 点击刷新");
        b.setBackgroundColor(0xFFC41A1A);
        b.setTextColor(0xFFFFFFFF);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, 48, Gravity.CENTER);
        root.addView(b, lp);
        b.setOnClickListener(v -> {
            v.setVisibility(View.GONE);
            refreshFromRemote();
        });
    }

    void refreshFromRemote() {
        io.execute(() -> {
            try {
                byte[] html = httpGet(REMOTE_HTML);
                if (html != null) {
                    final String content = new String(html, "UTF-8");
                    main.post(() -> {
                        hasRemoteUpdate = false;
                        View bar = root.findViewWithTag("update");
                        if (bar != null) bar.setVisibility(View.GONE);
                        ((SwipeRefreshLayout) root.getChildAt(0)).setRefreshing(false);
                        webView.loadDataWithBaseURL(null, content, "text/html", "UTF-8", null);
                    });
                } else {
                    main.post(() -> ((SwipeRefreshLayout) root.getChildAt(0)).setRefreshing(false));
                }
            } catch (Exception e) {
                main.post(() -> {
                    ((SwipeRefreshLayout) root.getChildAt(0)).setRefreshing(false);
                    Toast.makeText(this, "刷新失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    void checkOta(String remoteVer, String apkUrl, String sha) {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            String curVer = pi.versionName;
            if (apkUrl.isEmpty() || curVer.equals(remoteVer)) return;
            new AlertDialog.Builder(this)
                    .setTitle("发现新版本 " + remoteVer)
                    .setMessage("当前版本 " + curVer + "，是否下载更新？")
                    .setPositiveButton("更新", (d, w) -> downloadAndInstall(apkUrl, sha))
                    .setNegativeButton("稍后", null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    void downloadAndInstall(String url, String sha) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在下载更新…");
        pd.setCancelable(false);
        pd.show();
        io.execute(() -> {
            try {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ota");
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, "shise_update.apk");

                if (!sha.isEmpty()) {
                    File part = new File(dir, "shise_update.apk.part");
                    streamToFile(url, part);
                    if (!sha256(part).equalsIgnoreCase(sha)) {
                        part.delete();
                        throw new Exception("SHA-256 校验失败");
                    }
                    out.delete();
                    if (!part.renameTo(out)) copyFile(part, out);
                } else {
                    byte[] raw = httpGet(url);
                    FileOutputStream fos = new FileOutputStream(out);
                    fos.write(raw);
                    fos.close();
                }
                main.post(() -> {
                    pd.dismiss();
                    startInstall(out);
                });
            } catch (Exception e) {
                main.post(() -> {
                    pd.dismiss();
                    Toast.makeText(this, "更新失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    void startInstall(File apk) {
        if (Build.VERSION.SDK_INT >= 23) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, 100);
                    apkPending = apk;
                    return;
                } catch (Exception ignored) {
                }
            }
        }
        doInstall(apk);
    }

    File apkPending;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && apkPending != null) {
            File apk = apkPending;
            apkPending = null;
            if (getPackageManager().canRequestPackageInstalls()) {
                doInstall(apk);
            } else {
                Toast.makeText(this, "请先允许\"安装未知应用\"", Toast.LENGTH_LONG).show();
            }
        }
    }

    void doInstall(File apk) {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 24) {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
                i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.fromFile(apk));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动安装: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    void streamToFile(String url, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out));
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            bos.close();
            c.disconnect();
        }
    }

    void copyFile(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    byte[] httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("User-Agent", "shise/1.0");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            c.disconnect();
            return bos.toByteArray();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
