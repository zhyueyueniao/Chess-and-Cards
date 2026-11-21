package com.game65713.ccapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 纯原生启动更新：拉 manifest → 比较 versionCode → 下载校验 SHA256 → 调系统安装器。
 * 参照 yyn.remote 的 AppUpdaterPlugin，去掉 Capacitor 依赖，改为 Activity 内直接调用。
 */
public class AppUpdater {

    private final Activity activity;
    private final String baseUrl;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private ProgressDialog progress;

    public AppUpdater(Activity activity, String baseUrl) {
        this.activity = activity;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public void check() {
        workers.execute(() -> {
            try {
                String body = httpGet(baseUrl + "/api/app-release/manifest");
                JSONObject m = new JSONObject(body);
                int remote = m.optInt("versionCode", 0);
                int current = currentVersionCode();
                if (remote <= current) return;

                SharedPreferences sp = activity.getSharedPreferences("cc-update", Context.MODE_PRIVATE);
                if (sp.getInt("ignored-version", 0) == remote) return;

                String versionName = m.optString("versionName", "");
                String apk = m.optString("apk", "");
                String sha256 = m.optString("sha256", "");
                long size = m.optLong("size", 0);
                if (apk.isEmpty()) return;
                String url = apk.startsWith("http") ? apk
                        : baseUrl + "/api/app-release/download/" + apk;
                final String downloadUrl = url;
                final String expectedSha = sha256;
                ui.post(() -> showConfirm(remote, versionName, size, downloadUrl, expectedSha));
            } catch (Exception ignored) {
                // 检查失败静默跳过，不影响进入大厅
            }
        });
    }

    private void showConfirm(int code, String name, long size, String url, String sha) {
        String title = "发现新版本";
        String msg = "版本：" + (name.isEmpty() ? String.valueOf(code) : name)
                + "\n大小：" + (size > 0 ? (size / 1024) + " KB" : "未知")
                + "\n更新后将自动安装，请留意系统安装提示。";
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("立即更新", (d, w) -> startDownload(url, sha))
                .setNegativeButton("暂不更新", (d, w) -> {
                    activity.getSharedPreferences("cc-update", Context.MODE_PRIVATE)
                            .edit().putInt("ignored-version", code).apply();
                })
                .show();
    }

    private void startDownload(String url, String sha) {
        progress = new ProgressDialog(activity);
        progress.setCancelable(false);
        progress.setTitle("正在下载更新");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.show();
        workers.execute(() -> {
            File apk = new File(activity.getCacheDir(), ApkFileProvider.FILE_NAME);
            try {
                download(url, apk);
                String actual = sha256Of(apk);
                if (sha != null && !sha.isEmpty() && !sha.equalsIgnoreCase(actual)) {
                    throw new IllegalStateException("SHA256 校验不匹配，下载可能不完整");
                }
                ui.post(() -> {
                    if (progress != null && progress.isShowing()) progress.dismiss();
                    install(apk);
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "下载失败" : e.getMessage();
                ui.post(() -> {
                    if (progress != null && progress.isShowing()) progress.dismiss();
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void install(File apk) {
        Uri uri = ApkFileProvider.uriFor(activity);
        Intent base = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        String[] candidates = {
                null,
                "com.android.packageinstaller",
                "com.miui.packageinstaller",
                "com.google.android.packageinstaller"
        };
        for (String pkg : candidates) {
            Intent i = new Intent(base);
            if (pkg != null) i.setPackage(pkg);
            try {
                activity.startActivity(i);
                return;
            } catch (Exception ignored) {
                // 尝试下一个安装器
            }
        }
        Toast.makeText(activity, "无法打开安装器，请在系统设置中允许本应用安装未知应用",
                Toast.LENGTH_LONG).show();
    }

    private int currentVersionCode() {
        try {
            android.content.pm.PackageInfo pi = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        if (conn instanceof HttpsURLConnection) trustAll((HttpsURLConnection) conn);
        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            return new String(readAll(in), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private void download(String url, File dst) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        if (conn instanceof HttpsURLConnection) trustAll((HttpsURLConnection) conn);
        int total = conn.getContentLength();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int done = 0, n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    final int pct = Math.min(100, done * 100 / total);
                    ui.post(() -> {
                        if (progress != null && progress.isShowing()) progress.setProgress(pct);
                    });
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String sha256Of(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** Lucky 反向代理使用自签证书，与 WebView 的 onReceivedSslError(proceed) 保持一致 */
    private static void trustAll(HttpsURLConnection conn) throws Exception {
        TrustManager[] managers = new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, managers, new SecureRandom());
        conn.setSSLSocketFactory(ctx.getSocketFactory());
        HostnameVerifier all = (host, session) -> true;
        conn.setHostnameVerifier(all);
    }
}
