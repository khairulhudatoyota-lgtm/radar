package id.eky.radar;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.os.Message;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.provider.Settings;
import android.os.PowerManager;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.webkit.JsResult;
import android.webkit.JsPromptResult;
import android.widget.EditText;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {
    static final String RADAR_URL = "https://khairulhudatoyota-lgtm.github.io/radar/";
    static final String TLS_URL = "https://tls.toyota.astra.co.id/PembuatanSJKB";
    static final String TLS_HOST = "tls.toyota.astra.co.id";
    static final int REQ_FILE = 21;

    private WebView radar, tls, pop;
    private Button backBtn, tlsBtn, reloadBtn, valBtn, xlCopyBtn, xlDoneBtn;
    private WebView xl;
    private String xlLastUrl = "";
    private String xlTsv = "";
    private String sjId = null;
    private ProgressBar bar;
    private FrameLayout popRoot, popHolder;
    private String pendingPkg = null;
    private int tlsRedirects = 0;
    private String fillJs = "";
    private ValueCallback<Uri[]> fileCb = null;
    private Uri camUri = null;
    private long lastBack = 0;
    private boolean gpsDlg = false;
    static MainActivity inst;

    static void feed(final double lat, final double lng, final float acc) {
        final MainActivity a = inst;
        if (a == null) return;
        a.runOnUiThread(new Runnable() { @Override public void run() {
            try { a.radar.evaluateJavascript("if(window.radarBgPos)radarBgPos(" + lat + "," + lng + "," + acc + ");", null); } catch (Exception e) { }
        } });
    }

    // v61: tangkap PDF langsung dari halaman TLS (saat TLS membuat blob PDF), tanpa bergantung pada jendela popup yang putih
    private static final String PDFHOOK =
        "(function(){try{if(window.__rdHook)return;window.__rdHook=1;"
        + "var sent={};function send(b){try{if(!b||b.size<500||b.size>3000000)return;var k=b.size+':'+b.type;if(sent[k]&&Date.now()-sent[k]<8000)return;"
        + "var f=new FileReader();f.onload=function(){try{var s=String(f.result);var i=s.indexOf(',');var b64=s.substring(i+1);"
        + "if(atob(b64.substring(0,8)).indexOf('%PDF')!==0)return;sent[k]=Date.now();AndroidSJ.pdf('ILS.pdf',b64);}catch(e){}};f.readAsDataURL(b);}catch(e){}}"
        + "var oc=URL.createObjectURL;URL.createObjectURL=function(o){try{if(o instanceof Blob)send(o);}catch(e){}return oc.apply(this,arguments);};"
        + "var ofe=window.fetch;if(ofe){window.fetch=function(){var p=ofe.apply(this,arguments);try{p.then(function(r){try{var ct=r.headers.get('content-type')||'';if(ct.indexOf('pdf')>=0)r.clone().blob().then(send);}catch(e){}});}catch(e){}return p;};}"
        + "var ox=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(){this.addEventListener('load',function(){try{var ct=this.getResponseHeader('content-type')||'';if(ct.indexOf('pdf')>=0&&this.response){var r=this.response;if(r instanceof Blob)send(r);else if(r instanceof ArrayBuffer)send(new Blob([r],{type:'application/pdf'}));}}catch(e){}});return ox.apply(this,arguments);};"
        + "}catch(e){}})();";

    private static final String SHIM =
        "(function(){ if(window.__radarShim) return; window.__radarShim=1;"
        + "var N=function(t,o){ try{ AndroidTLS.notify(String(t), (o&&o.body)?String(o.body):''); }catch(e){} };"
        + "N.permission='granted'; N.requestPermission=function(){ return Promise.resolve('granted'); };"
        + "try{ window.Notification=N; }catch(e){}"
        + "var oc=HTMLAnchorElement.prototype.click;"
        + "HTMLAnchorElement.prototype.click=function(){ var a=this;"
        + " if(a.download && a.href && a.href.indexOf('blob:')===0){"
        + "  fetch(a.href).then(function(r){return r.blob();}).then(function(b){"
        + "   var fr=new FileReader(); fr.onload=function(){ var s=String(fr.result);"
        + "    AndroidTLS.saveFile(a.download, b.type||'application/octet-stream', s.substring(s.indexOf(',')+1)); };"
        + "   fr.readAsDataURL(b); });"
        + "  return; }"
        + " return oc.apply(this, arguments); };"
        + "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inst = this;
        getWindow().setStatusBarColor(Color.parseColor("#0F2A4A"));
        getWindow().setNavigationBarColor(Color.parseColor("#0F2A4A"));
        try {
            InputStream in = getAssets().open("tls_fill.js");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            fillJs = bo.toString("UTF-8");
        } catch (Exception e) {
            fillJs = "";
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        radar = new WebView(this);
        tls = new WebView(this);
        root.addView(radar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(tls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tls.setVisibility(View.GONE);
        xl = new WebView(this);
        root.addView(xl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        xl.setVisibility(View.GONE);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10, Gravity.TOP));

        backBtn = makeBtn("\u2190 RADAR");
        reloadBtn = makeBtn("\u21BB");
        tlsBtn = makeBtn("\uD83C\uDF10 TLS");
        tlsBtn.setVisibility(View.GONE);
        valBtn = makeBtn("\u2714 Validasi selesai");
        valBtn.setBackgroundColor(Color.parseColor("#E61F1F44"));
        valBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.topMargin = 24;
        lp.leftMargin = 24;
        FrameLayout.LayoutParams lpR = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpR.gravity = Gravity.TOP | Gravity.START;
        lpR.topMargin = 250;
        lpR.leftMargin = 24;
        FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.gravity = Gravity.BOTTOM | Gravity.END;
        lp2.bottomMargin = 160;
        lp2.rightMargin = 24;
        backBtn.setVisibility(View.GONE);
        reloadBtn.setVisibility(View.GONE);
        root.addView(backBtn, lp);
        root.addView(reloadBtn, lpR);
        root.addView(tlsBtn, lp2);
        FrameLayout.LayoutParams lpV = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpV.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lpV.bottomMargin = 120;
        root.addView(valBtn, lpV);
        xlCopyBtn = makeBtn("\uD83D\uDCE5 Tempel manifest");
        xlCopyBtn.setBackgroundColor(Color.parseColor("#E6107C41"));
        xlCopyBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams lpX = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpX.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lpX.bottomMargin = 120;
        root.addView(xlCopyBtn, lpX);
        xlCopyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { xlPaste(); }
        });
        xlDoneBtn = makeBtn("\u2714 Selesai \u2014 kembali ke RADAR");
        xlDoneBtn.setBackgroundColor(Color.parseColor("#E6107C41"));
        xlDoneBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams lpD = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpD.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lpD.bottomMargin = 240;
        root.addView(xlDoneBtn, lpD);
        xlDoneBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRadar(); }
        });
        valBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (sjId == null) return;
                final String id = sjId;
                showRadar();
                radar.evaluateJavascript("if(window.validasiSelesai)validasiSelesai(" + id + ");", null);
            }
        });
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRadar(); }
        });
        reloadBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (xl.getVisibility() == View.VISIBLE) xl.reload(); else tls.reload(); }
        });
        tlsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showTls();
                { String cu = tls.getUrl(); if (cu == null || cu.startsWith("about:")) tls.loadUrl(TLS_URL); }
            }
        });

        // lapisan jendela popup (cetak ILS, tautan WhatsApp)
        popRoot = new FrameLayout(this);
        popRoot.setBackgroundColor(Color.WHITE);
        popRoot.setVisibility(View.GONE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(Color.parseColor("#0F2A4A"));
        Button bClose = makeBtn("\u2715 Tutup");
        Button bPrint = makeBtn("\uD83D\uDDA8 Cetak / PDF");
        bSave = makeBtn("\uD83D\uDCBE Jadikan surat jalan");
        row.addView(bClose);
        row.addView(bPrint);
        row.addView(bSave);
        popHolder = new FrameLayout(this);
        col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(popHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        popRoot.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(popRoot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closePopup(); }
        });
        bPrint.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { printPopup(); }
        });
        bSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (popPdfData == null) { Toast.makeText(MainActivity.this, "PDF belum siap, tunggu sebentar.", Toast.LENGTH_SHORT).show(); return; }
                final String nm = popPdfName == null ? "surat-jalan.pdf" : popPdfName;
                final String b64 = Base64.encodeToString(popPdfData, Base64.NO_WRAP);
                closePopup();
                showRadar();
                radar.evaluateJavascript("if(window.sjPickStore)sjPickStore(" + JSONObject.quote(nm) + ",'" + b64 + "');", null);
            }
        });
        setContentView(root);
        tlsH.postDelayed(tlsTick, 60 * 1000L);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(radar, true);
        cm.setAcceptThirdPartyCookies(tls, true);

        setupWebView(radar);
        setupWebView(tls);
        // Login TAM Passport: matikan isi-otomatis & simpan form (isian lama / captcha basi ikut terkirim => "Invalid username or password")
        try { tls.getSettings().setSaveFormData(false); } catch (Exception e) { }
        try { if (Build.VERSION.SDK_INT >= 26) tls.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); } catch (Exception e) { }
        setupWebView(xl);
        cm.setAcceptThirdPartyCookies(xl, true);
        xl.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");   // tampilan desktop: Excel Online tidak memaksa buka aplikasi Excel
        xl.getSettings().setSupportZoom(true);
        xl.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl(); String sc = u.getScheme() == null ? "" : u.getScheme();
                if (sc.equals("http") || sc.equals("https") || sc.equals("about") || sc.equals("data") || sc.equals("blob") || sc.equals("javascript")) return false;
                return true;   // ms-excel:// dan skema lain diabaikan: tetap di dalam aplikasi
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showOffline(v, v.getUrl() == null ? "https://www.office.com" : v.getUrl());
            }
        });
        radar.addJavascriptInterface(new Bridge(), "AndroidTLS");
        tls.addJavascriptInterface(new SjBridge(), "AndroidSJ");

        radar.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleExternal(r.getUrl(), true);
            }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(SHIM, null);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showOffline(v, RADAR_URL);
            }
        });
        tls.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String h = u.getHost() == null ? "" : u.getHost();
                // semua halaman web (termasuk login TAM Passport) tetap dibuka DI DALAM aplikasi
                if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) return false;
                if ("about".equals(u.getScheme()) || "data".equals(u.getScheme()) || "blob".equals(u.getScheme()) || "javascript".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
                return true;
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showOffline(v, TLS_URL);
            }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(PDFHOOK, null);
                if (pendingPkg == null || url == null) return;
                Uri u = Uri.parse(url);
                String h = u.getHost() == null ? "" : u.getHost();
                String p = u.getPath() == null ? "" : u.getPath();
                if (!h.equals(TLS_HOST)) return;               // masih di halaman login TAM Passport
                if (p.indexOf("PembuatanSJKB") >= 0) {
                    String pkg = pendingPkg;
                    pendingPkg = null;
                    runFill(pkg);
                } else if (!p.startsWith("/auth") && tlsRedirects < 3) {
                    tlsRedirects++;                              // sudah login, arahkan ke halaman Pembuatan ILS
                    v.loadUrl(TLS_URL);
                }
            }
        });

        ArrayList<String> need = new ArrayList<String>();
        need.add(Manifest.permission.CAMERA);
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(need.toArray(new String[0]), 11);
        radar.loadUrl(RADAR_URL);
        checkApkUpdate();
    }

    // ===== Update APK otomatis (v54): unduh sendiri, driver tinggal tekan Pasang =====
    private AlertDialog updDlg;
    private boolean updRunning = false;
    private Uri updUri;

    private void updShow(final String title, final String msg, final String btn, final Runnable act, final boolean skip) {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (isFinishing()) return;
            try { if (updDlg != null) updDlg.dismiss(); } catch (Exception e) { }
            AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this).setTitle(title).setMessage(msg).setCancelable(false);
            if (btn != null) b.setPositiveButton(btn, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { if (act != null) act.run(); } });
            if (skip) b.setNegativeButton("Lewati", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { updRunning = false; } });
            updDlg = b.create();
            updDlg.show();
            if (btn != null) {
                // tombol positif tidak menutup dialog otomatis; ditutup oleh dialog berikutnya
                updDlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { if (act != null) act.run(); } });
            }
        } });
    }

    private void checkApkUpdate() {
        new Thread(new Runnable() { @Override public void run() {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://khairulhudatoyota-lgtm.github.io/radar/version.json?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[1024]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                final int latest = new JSONObject(bo.toString("UTF-8")).getInt("code");
                final int mine = (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
                if (latest <= mine) return;
                runOnUiThread(new Runnable() { @Override public void run() { beginAutoUpdate(); } });
            } catch (Exception e) { }
        } }).start();
    }

    private void beginAutoUpdate() {
        if (updRunning) return;
        updRunning = true;
        try { if (permDlg != null) permDlg.dismiss(); } catch (Exception e) { }
        if (!getPackageManager().canRequestPackageInstalls()) {
            updShow("Pembaruan RADAR", "Ada versi baru. Izinkan RADAR memasang aplikasi, lalu kembali ke sini.", "Izinkan", new Runnable() { @Override public void run() {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) { }
                updRunning = false;
            } }, true);
            return;
        }
        startApkDownload();
    }

    private void launchInstall() {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(updUri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "Gagal membuka pemasang: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startApkDownload() {
        try {
            java.io.File old = new java.io.File(getExternalFilesDir(null), "radar-update.apk");
            if (old.exists()) old.delete();
            final android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request rq = new android.app.DownloadManager.Request(Uri.parse("https://khairulhudatoyota-lgtm.github.io/radar/radar.apk?t=" + System.currentTimeMillis()));
            rq.setTitle("RADAR EKY - pembaruan");
            rq.setMimeType("application/vnd.android.package-archive");
            rq.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE);
            rq.setDestinationInExternalFilesDir(this, null, "radar-update.apk");
            final long id = dm.enqueue(rq);
            updShow("Memperbarui RADAR", "Mengunduh versi baru... mohon tunggu.", null, null, false);
            new Thread(new Runnable() { @Override public void run() {
                try {
                    for (int i = 0; i < 300; i++) {
                        Thread.sleep(1000);
                        android.database.Cursor cu = dm.query(new android.app.DownloadManager.Query().setFilterById(id));
                        if (cu == null) continue;
                        try {
                            if (!cu.moveToFirst()) break;
                            int st = cu.getInt(cu.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS));
                            if (st == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                                updUri = dm.getUriForDownloadedFile(id);
                                updShow("Pembaruan siap", "Versi baru sudah diunduh. Tekan Pasang, lalu Pasang lagi di layar berikutnya.", "Pasang", new Runnable() {
                                    @Override public void run() { launchInstall(); } }, false);
                                runOnUiThread(new Runnable() { @Override public void run() { launchInstall(); } });
                                return;
                            }
                            if (st == android.app.DownloadManager.STATUS_FAILED) break;
                        } finally { cu.close(); }
                    }
                } catch (Exception e) { }
                updShow("Pembaruan gagal", "Unduhan tidak berhasil. Periksa internet lalu coba lagi.", "Coba lagi", new Runnable() {
                    @Override public void run() { startApkDownload(); } }, true);
            } }).start();
        } catch (Exception e) {
            updShow("Pembaruan gagal", "Tidak bisa memulai unduhan: " + e.getMessage(), "Coba lagi", new Runnable() {
                @Override public void run() { startApkDownload(); } }, true);
        }
    }

    // ===== Panduan izin (v55): periksa izin satu per satu, buka layar yang tepat =====
    private boolean fg = false;
    private AlertDialog permDlg;
    private static boolean permSkip = false;

    private void openAppSettings() {
        try {
            Intent it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            it.setData(Uri.parse("package:" + getPackageName()));
            startActivity(it);
        } catch (Exception e) { }
    }

    private void permWizard() {
        try {
            if (permSkip || isFinishing() || updRunning) return;
            if (permDlg != null && permDlg.isShowing()) return;
            if (updDlg != null && updDlg.isShowing()) return;
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;   // kotak izin sistem dari onCreate masih berjalan
            final ArrayList<String> ttl = new ArrayList<String>();
            final ArrayList<String> msg = new ArrayList<String>();
            final ArrayList<Runnable> act = new ArrayList<Runnable>();
            if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ttl.add("Lokasi sepanjang waktu");
                msg.add("Supaya posisi truk tetap terkirim ke peta saat RADAR di-minimize atau layar terkunci, pilih Lokasi lalu Izinkan sepanjang waktu.");
                act.add(new Runnable() { @Override public void run() {
                    if (Build.VERSION.SDK_INT == 29) requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 12);
                    else openAppSettings();
                } });
            }
            final SharedPreferences wsp = getSharedPreferences("radar", MODE_PRIVATE);
            boolean arItem = false;
            if (Build.VERSION.SDK_INT >= 30 && !wsp.getBoolean("ignAR", false)) {
                boolean ex = true;
                try { ex = getPackageManager().isAutoRevokeWhitelisted(); } catch (Exception e) { }
                if (!ex) {
                    arItem = true;
                    ttl.add("Matikan Jeda aktivitas aplikasi");
                    msg.add("Di layar berikutnya MATIKAN tombol 'Jeda aktivitas apl jika tidak digunakan' (di sebagian HP bernama 'Hapus izin jika aplikasi tidak digunakan'). Kalau dibiarkan hidup, HP diam-diam mencabut izin lokasi dan posisi truk berhenti masuk ke peta walaupun izin terlihat aktif.");
                    act.add(new Runnable() { @Override public void run() {
                        try {
                            Intent it = new Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS);
                            it.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(it);
                        } catch (Exception e) { openAppSettings(); }
                    } });
                }
            }
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                ttl.add("Baterai tanpa pembatasan");
                msg.add("Supaya RADAR tidak dimatikan HP di latar belakang, pilih Izinkan (atau Tanpa pembatasan) di layar berikutnya.");
                act.add(new Runnable() { @Override public void run() {
                    try {
                        Intent it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        it.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(it);
                    } catch (Exception e) { openAppSettings(); }
                } });
            }
            String mf = String.valueOf(Build.MANUFACTURER).toLowerCase();
            if ((mf.contains("xiaomi") || mf.contains("redmi") || mf.contains("poco")) && !wsp.getBoolean("asAsked", false)) {
                ttl.add("Mulai otomatis (Xiaomi)");
                msg.add("Di layar berikutnya aktifkan 'Mulai otomatis' untuk RADAR EKY. Lalu buka menu aplikasi terbaru (Recent), tahan kartu RADAR EKY dan pilih Kunci (ikon gembok) supaya tidak ditutup HP.");
                act.add(new Runnable() { @Override public void run() {
                    wsp.edit().putBoolean("asAsked", true).apply();
                    try {
                        Intent it = new Intent();
                        it.setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity");
                        startActivity(it);
                    } catch (Exception e) { openAppSettings(); }
                } });
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ttl.add("Notifikasi");
                msg.add("Supaya peringatan dan alarm RADAR berbunyi, aktifkan Notifikasi untuk RADAR.");
                act.add(new Runnable() { @Override public void run() {
                    try {
                        Intent it = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        it.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                        startActivity(it);
                    } catch (Exception e) { openAppSettings(); }
                } });
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ttl.add("Kamera");
                msg.add("Kamera dipakai untuk foto defect dan pindai QR. Pilih Izin lalu Kamera lalu Izinkan.");
                act.add(new Runnable() { @Override public void run() { openAppSettings(); } });
            }
            if (ttl.isEmpty()) return;
            AlertDialog.Builder pb = new AlertDialog.Builder(this)
                .setTitle("Pengaturan RADAR (" + ttl.size() + " belum beres)")
                .setMessage(ttl.get(0) + "\n\n" + msg.get(0))
                .setCancelable(false)
                .setPositiveButton("Buka", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { act.get(0).run(); } })
                .setNegativeButton("Nanti", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { permSkip = true; } });
            if (arItem && ttl.get(0).startsWith("Matikan Jeda")) {
                pb.setNeutralButton("Sudah saya matikan", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { wsp.edit().putBoolean("ignAR", true).apply(); } });
            }
            permDlg = pb.create();
            permDlg.show();
        } catch (Exception e) { }
    }

    private Button makeBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#CC0F2A4A"));
        return b;
    }

    private boolean handleExternal(Uri u, boolean fromRadar) {
        String sch = u.getScheme() == null ? "" : u.getScheme();
        String h = u.getHost() == null ? "" : u.getHost();
        if (sch.equals("about") || sch.equals("data") || sch.equals("blob") || sch.equals("javascript")) return false;
        if (h.endsWith("github.io") || h.endsWith("firebaseio.com") || h.endsWith("firebaseapp.com")) return false;
        boolean web = sch.equals("http") || sch.equals("https");
        boolean gmaps = h.contains("google.") || h.endsWith("goo.gl") || h.endsWith("maps.app.goo.gl");
        if (web && !gmaps) {
            // semua halaman web (TLS, Passport domain apa pun, PDF surat jalan) dibuka DI DALAM aplikasi; hanya Google Maps & skema non-web yang keluar
            if (fromRadar) { showTls(); tls.loadUrl(u.toString()); return true; }
            return false;
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
        return true;
    }

    private void setupWebView(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true);
        try { w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false); } catch (Exception e) { }   // JS (kirim posisi ke peta) tidak dibekukan saat aplikasi di-minimize
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(new Runnable() { @Override public void run() { r.grant(r.getResources()); } });
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, false);
            }
            @Override public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                return openFileChooser(cb, p);
            }
            @Override public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                return openPopup(resultMsg);
            }
            @Override public void onCloseWindow(WebView v) {
                if (v == pop) closePopup();
            }
            @Override public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setCancelable(false)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { r.confirm(); } }).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setCancelable(false)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { r.confirm(); } })
                    .setNegativeButton("Batal", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { r.cancel(); } }).show();
                return true;
            }
            @Override public boolean onJsPrompt(WebView v, String url, String msg, String def, final JsPromptResult r) {
                final EditText et = new EditText(MainActivity.this);
                et.setText(def == null ? "" : def);
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setView(et).setCancelable(false)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { r.confirm(et.getText().toString()); } })
                    .setNegativeButton("Batal", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { r.cancel(); } }).show();
                return true;
            }
        });
        final WebView src = w;
        w.setDownloadListener(new android.webkit.DownloadListener() {
            @Override public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                onPdfDownload(src, url, ua, cd, mime);
            }
        });
    }

    // ---------- pilih file / foto (DCCP Excel, katalog, foto defect) ----------
    private boolean openFileChooser(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) {
        if (fileCb != null) { fileCb.onReceiveValue(null); }
        fileCb = cb;
        camUri = null;
        Intent chooser = Intent.createChooser(p.createIntent(), "Pilih file");
        boolean wantImg = p.isCaptureEnabled();
        String[] types = p.getAcceptTypes();
        if (types != null) {
            for (String t : types) { if (t != null && t.startsWith("image")) wantImg = true; }
        }
        if (wantImg) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, "radar_" + System.currentTimeMillis() + ".jpg");
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                camUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cam.putExtra(MediaStore.EXTRA_OUTPUT, camUri);
                if (p.isCaptureEnabled()) {
                    chooser = cam;
                } else {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                }
            } catch (Exception e) {
                camUri = null;
            }
        }
        try {
            startActivityForResult(chooser, REQ_FILE);
        } catch (Exception e) {
            fileCb = null;
            cb.onReceiveValue(null);
            return false;
        }
        return true;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK) {
                if (data != null && (data.getData() != null || data.getClipData() != null)) {
                    r = WebChromeClient.FileChooserParams.parseResult(res, data);
                } else if (camUri != null) {
                    r = new Uri[]{camUri};
                }
            } else if (camUri != null) {
                try { getContentResolver().delete(camUri, null, null); } catch (Exception e) { }
            }
            fileCb.onReceiveValue(r);
            fileCb = null;
            camUri = null;
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    // ---------- jendela popup (cetak ILS) ----------
    private boolean openPopup(Message resultMsg) {
        closePopup();
        pop = new WebView(this);
        setupWebView(pop);
        pop.addJavascriptInterface(new SjBridge(), "AndroidSJ");
        pop.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                boolean ext = handleExternal(r.getUrl(), false);
                if (ext) closePopup();
                return ext;
            }
            @Override public void onPageFinished(WebView v, String url) {
                // PDF berbentuk blob: WebView tidak bisa menampilkannya (layar putih) -> ambil lalu tampilkan sendiri
                v.evaluateJavascript("(function(){try{var r={u:location.href,t:document.contentType,l:(document.body?document.body.innerText.length:0),h:(document.body?document.body.innerHTML.length:0)};var e=document.querySelector('embed[src],iframe[src],object[data]');if(e)r.e=e.src||e.data||'';AndroidSJ.probe(JSON.stringify(r));}catch(x){AndroidSJ.probe(JSON.stringify({err:String(x)}));}})();", null);
                if (url != null && url.startsWith("blob:")) {
                    v.evaluateJavascript("(function(){fetch(" + JSONObject.quote(url) + ").then(function(r){return r.blob();}).then(function(b){if(String(b.type).indexOf('pdf')<0)return;var f=new FileReader();f.onload=function(){var s=String(f.result);AndroidSJ.pdf('ILS.pdf',s.substring(s.indexOf(',')+1));};f.readAsDataURL(b);});})();", null);
                }
            }
        });
        popHolder.addView(pop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final WebView mine = pop;
        final int[] tick = new int[]{0};
        final android.os.Handler hh = new android.os.Handler(android.os.Looper.getMainLooper());
        hh.postDelayed(new Runnable() { @Override public void run() {
            if (pop != mine || popPdfData != null || tick[0]++ > 30) return;
            try {
                mine.evaluateJavascript("(function(){try{var r={p:1,u:location.href,l:0,h:0};var e=document.querySelector('embed[src],iframe[src],object[data]');if(e)r.e=e.src||e.data||'';if(r.e||(location.href.indexOf('blob:')===0))AndroidSJ.probe(JSON.stringify(r));if(location.href.indexOf('blob:')===0){fetch(location.href).then(function(x){return x.blob();}).then(function(b){var f=new FileReader();f.onload=function(){var s=String(f.result);AndroidSJ.pdf('ILS.pdf',s.substring(s.indexOf(',')+1));};f.readAsDataURL(b);});}}catch(x){}})();", null);
            } catch (Exception ex) { }
            hh.postDelayed(this, 700);
        } }, 700);
        popRoot.setVisibility(View.VISIBLE);
        backBtn.setVisibility(View.GONE); reloadBtn.setVisibility(View.GONE);
        WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
        t.setWebView(pop);
        resultMsg.sendToTarget();
        return true;
    }

    // ---------- penampil PDF bawaan (WebView tidak bisa menampilkan PDF) ----------
    private int lastPdfHash = 0;
    private long lastPdfAt = 0;
    private Button bSave;
    private byte[] popPdfData;
    private String popPdfName;
    private void showPdfViewer(final String name, final byte[] data) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                java.io.File f = new java.io.File(getCacheDir(), "view.pdf");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                fo.write(data); fo.close();
                android.graphics.pdf.PdfRenderer pr = new android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY));
                final int wpx = getResources().getDisplayMetrics().widthPixels;
                final ArrayList<android.graphics.Bitmap> pages = new ArrayList<android.graphics.Bitmap>();
                int cnt = Math.min(pr.getPageCount(), 12);
                for (int i = 0; i < cnt; i++) {
                    android.graphics.pdf.PdfRenderer.Page pg = pr.openPage(i);
                    int bw = wpx * 2;
                    int bh = Math.max(1, Math.round((float) bw * pg.getHeight() / pg.getWidth()));
                    android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
                    bm.eraseColor(Color.WHITE);
                    pg.render(bm, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    pg.close();
                    pages.add(bm);
                }
                pr.close();
                runOnUiThread(new Runnable() { @Override public void run() {
                    popPdfData = data; popPdfName = name;
                    android.widget.ScrollView sv = new android.widget.ScrollView(MainActivity.this);
                    sv.setBackgroundColor(Color.parseColor("#DDDDDD"));
                    LinearLayout ll = new LinearLayout(MainActivity.this);
                    ll.setOrientation(LinearLayout.VERTICAL);
                    for (android.graphics.Bitmap bm : pages) {
                        android.widget.ImageView iv = new android.widget.ImageView(MainActivity.this);
                        iv.setImageBitmap(bm);
                        iv.setAdjustViewBounds(true);
                        LinearLayout.LayoutParams lpi = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        lpi.bottomMargin = 8;
                        ll.addView(iv, lpi);
                    }
                    sv.addView(ll);
                    popHolder.removeAllViews();
                    if (pop != null) { try { pop.destroy(); } catch (Exception e) { } pop = null; }
                    popHolder.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    popRoot.setVisibility(View.VISIBLE);
                    backBtn.setVisibility(View.GONE); reloadBtn.setVisibility(View.GONE);
                } });
            } catch (final Exception e) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    Toast.makeText(MainActivity.this, "Gagal menampilkan PDF: " + e.getMessage(), Toast.LENGTH_LONG).show(); } });
            }
        } }).start();
    }

    private void closePopup() {
        popPdfData = null;
        popRoot.setVisibility(View.GONE);
        if (tls != null && tls.getVisibility() == View.VISIBLE) { backBtn.setVisibility(View.VISIBLE); reloadBtn.setVisibility(View.VISIBLE); }
        popHolder.removeAllViews();
        if (pop != null) {
            try { pop.destroy(); } catch (Exception e) { }
            pop = null;
        }
    }

    private void printPopup() {
        if (popPdfData != null) {
            try {
                Uri uri = saveBytes(popPdfName, "application/pdf", popPdfData);
                Intent it = new Intent(Intent.ACTION_VIEW);
                it.setDataAndType(uri, "application/pdf");
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(Intent.createChooser(it, "Cetak / simpan PDF"));
            } catch (Exception e) {
                Toast.makeText(this, "Tidak bisa membuka PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (pop == null) return;
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            pm.print("RADAR ILS", pop.createPrintDocumentAdapter("RADAR ILS"), new PrintAttributes.Builder().build());
        } catch (Exception e) {
            Toast.makeText(this, "Cetak tidak tersedia: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------- layar tanpa koneksi ----------
    private void showOffline(WebView v, String url) {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
            + "<body style='font-family:sans-serif;text-align:center;padding:48px 24px;color:#0F2A4A'>"
            + "<h2>Tidak ada koneksi</h2><p>Periksa internet HP, lalu coba lagi.</p>"
            + "<p><a href='" + url + "' style='display:inline-block;background:#0F2A4A;color:#fff;padding:14px 28px;border-radius:10px;text-decoration:none;font-weight:bold'>Coba lagi</a></p>"
            + "</body></html>";
        v.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    // ---------- surat jalan: tangkap PDF dari TLS ----------
    private boolean isPdf(String url, String cd, String mime) {
        String m = mime == null ? "" : mime.toLowerCase();
        String u = url == null ? "" : url.toLowerCase();
        String c = cd == null ? "" : cd.toLowerCase();
        return m.contains("pdf") || u.endsWith(".pdf") || c.contains(".pdf");
    }
    private String pdfName(String cd, String url) {
        String n = "surat-jalan-" + (sjId == null ? "" : sjId) + ".pdf";
        try {
            if (cd != null) {
                int i = cd.toLowerCase().indexOf("filename=");
                if (i >= 0) {
                    String f = cd.substring(i + 9).replace("\"", "").trim();
                    int sc = f.indexOf(';'); if (sc > 0) f = f.substring(0, sc);
                    if (f.length() > 0) n = f;
                }
            }
        } catch (Exception e) { }
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
    private void onPdfDownload(final WebView src, final String url, final String ua, final String cd, final String mime) {
        if (src == radar) return;
        if (!isPdf(url, cd, mime)) return;
        final String name = pdfName(cd, url);
        if (url.startsWith("blob:")) {
            final String js = "(function(){fetch(" + JSONObject.quote(url) + ").then(function(r){return r.blob();}).then(function(b){var f=new FileReader();f.onload=function(){var s=String(f.result);AndroidSJ.pdf(" + JSONObject.quote(name) + ",s.substring(s.indexOf(',')+1));};f.readAsDataURL(b);});})();";
            runOnUiThread(new Runnable() { @Override public void run() { src.evaluateJavascript(js, null); } });
        } else {
            Uri u = Uri.parse(url);
            String h = u.getHost() == null ? "" : u.getHost();
            if (!h.endsWith("astra.co.id")) return;
            new Thread(new Runnable() { @Override public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestProperty("User-Agent", ua);
                    String ck = CookieManager.getInstance().getCookie(url);
                    if (ck != null) c.setRequestProperty("Cookie", ck);
                    c.setConnectTimeout(15000); c.setReadTimeout(30000);
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 3000000) throw new Exception("PDF terlalu besar"); }
                    in.close();
                    byte[] got = bo.toByteArray();
                    if (sjId != null) deliverPdf(name, Base64.encodeToString(got, Base64.NO_WRAP));
                    else showPdfViewer(name, got);
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() { @Override public void run() {
                        Toast.makeText(MainActivity.this, "Gagal mengambil PDF: " + e.getMessage() + ". Unggah manual di RADAR.", Toast.LENGTH_LONG).show(); } });
                }
            } }).start();
        }
    }
    private void fetchPdfNative(final String url, final String ua, final String name) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("User-Agent", ua);
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null) c.setRequestProperty("Cookie", ck);
                c.setConnectTimeout(15000); c.setReadTimeout(30000);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 6000000) throw new Exception("PDF terlalu besar"); }
                in.close();
                byte[] got = bo.toByteArray();
                if (got.length < 5 || got[0] != '%' || got[1] != 'P') throw new Exception("bukan PDF");
                if (sjId != null) deliverPdf(name, Base64.encodeToString(got, Base64.NO_WRAP));
                else showPdfViewer(name, got);
            } catch (final Exception e) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    Toast.makeText(MainActivity.this, "Gagal mengambil PDF: " + e.getMessage(), Toast.LENGTH_LONG).show(); } });
            }
        } }).start();
    }
    private void deliverPdf(final String name, final String b64) {
        if (sjId == null) return;
        final String id = sjId;
        runOnUiThread(new Runnable() { @Override public void run() {
            radar.evaluateJavascript("if(window.radarSJ)radarSJ('" + id + "'," + JSONObject.quote(name) + ",'" + b64 + "');", null);
            Toast.makeText(MainActivity.this, "Surat jalan ditangkap. Cek di RADAR setelah tekan Validasi selesai.", Toast.LENGTH_LONG).show();
        } });
        try { showPdfViewer(name, Base64.decode(b64, Base64.DEFAULT)); } catch (Exception e) { }
    }
    private Uri saveBytes(String name, String mime, byte[] data) throws Exception {
        String safe = (name == null || name.length() == 0) ? "radar-file" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, (mime == null || mime.length() == 0) ? "application/octet-stream" : mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        OutputStream os = getContentResolver().openOutputStream(uri);
        os.write(data);
        os.close();
        return uri;
    }

    private void runFill(String pkg) {
        String js = "(function(){function run(){window.__RADAR_PKG=" + JSONObject.quote(pkg) + ";" + fillJs + "}"
                + "function w(n){ if(document.querySelector('form[name=\"PembuatanSJKBForm\"]')||n>60){run();} else {setTimeout(function(){w(n+1);},500);} }"
                + "w(0);})();";
        tls.evaluateJavascript(js, null);
    }

    // ---------- Sesi TLS dibatasi 12 jam: akun TLS terbatas jumlah login-nya, jadi sesi otomatis dikeluarkan ----------
    private static final long TLS_SESSION_MS = 6L * 3600L * 1000L;   // sama dengan TLS di Chrome/Edge: tidak dipakai 6 jam = logout total
    private final android.os.Handler tlsH = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tlsTick = new Runnable() { @Override public void run() {
        try { if (tls != null && tls.getVisibility() != View.VISIBLE) tlsSessionCheck(false); } catch (Exception e) { }
        tlsH.postDelayed(this, 5 * 60 * 1000L);
    } };

    private static final int[] TLS_SLOT_HOURS = {0, 6, 12, 18, 20};
    private long tlsLastSlot(long now) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(now);
        long best = 0;
        for (int dayBack = 0; dayBack <= 1; dayBack++) {
            for (int h : TLS_SLOT_HOURS) {
                java.util.Calendar x = (java.util.Calendar) c.clone();
                x.add(java.util.Calendar.DAY_OF_YEAR, -dayBack);
                x.set(java.util.Calendar.HOUR_OF_DAY, h); x.set(java.util.Calendar.MINUTE, 0); x.set(java.util.Calendar.SECOND, 0); x.set(java.util.Calendar.MILLISECOND, 0);
                long t = x.getTimeInMillis();
                if (t <= now && t > best) best = t;
            }
        }
        return best;
    }

    /** Sama seperti TLS di Chrome/Edge: sesi dianggap habis bila TLS tidak dipakai 6 jam. "tlsSince" = waktu terakhir TLS dipakai.
     *  open=true: TLS akan dibuka (cek dulu, lalu catat waktu pakai). open=false: hanya keluarkan sesi bila sudah 6 jam tidak dipakai. */
    private void tlsSessionCheck(boolean open) {
        try {
            SharedPreferences sp = getSharedPreferences("radar", MODE_PRIVATE);
            long now = System.currentTimeMillis(), last = sp.getLong("tlsSince", 0);
            // Jadwal logout paksa TLS: 00.00, 06.00, 12.00, 18.00, 20.00 (waktu HP). Saat TLS sedang dibuka, ditunda sampai ditutup.
            long slot = tlsLastSlot(now), done = sp.getLong("tlsSlotDone", 0);
            if (done == 0) { sp.edit().putLong("tlsSlotDone", slot).apply(); }
            else if (done < slot && (open || tls == null || tls.getVisibility() != View.VISIBLE)) {
                sp.edit().putLong("tlsSlotDone", slot).apply();
                if (last > 0) {
                    tlsLogout(false);
                    last = 0;
                    sp.edit().putLong("tlsSince", 0).apply();
                    if (open) Toast.makeText(this, "Waktunya logout TLS terjadwal. Silakan login TLS lagi.", Toast.LENGTH_LONG).show();
                }
            }
            if (last > 0 && now - last > TLS_SESSION_MS) {
                tlsLogout(false);
                last = 0;
                sp.edit().putLong("tlsSince", 0).apply();
                if (open) Toast.makeText(this, "TLS tidak dipakai 6 jam. Silakan login TLS lagi.", Toast.LENGTH_LONG).show();
            }
            if (open) sp.edit().putLong("tlsSince", now).apply();
        } catch (Exception e) { }
    }

    private void tlsTouch() {
        try { getSharedPreferences("radar", MODE_PRIVATE).edit().putLong("tlsSince", System.currentTimeMillis()).apply(); } catch (Exception e) { }
    }

    // Keluar dari TLS: coba logout di sisi server dulu (agar slot login akun TLS dibebaskan), baru hapus cookie/data lokal
    private final Runnable tlsIdleLogout = new Runnable() { @Override public void run() {
        try { if (tls != null && (!fg || tls.getVisibility() != View.VISIBLE)) tls.loadUrl("about:blank"); } catch (Exception e) { }   // seperti menutup tab: halaman berhenti, login tetap
    } };

    private void tlsLogout() { tlsLogout(true); }

    private void tlsLogout(boolean serverFirst) {
        try { getSharedPreferences("radar", MODE_PRIVATE).edit().putLong("tlsSince", 0).apply(); } catch (Exception e) { }
        if (serverFirst) {
            try {
                String js = "(function(){try{var as=document.querySelectorAll('a[href],form[action]');var done={};"
                    + "for(var i=0;i<as.length;i++){var u=as[i].href||as[i].action||'';var t=(as[i].textContent||'');"
                    + "if(/log[-_ ]?out|log[-_ ]?off|signout|sign[-_ ]out|keluar/i.test(u+' '+t)&&!done[u]){done[u]=1;"
                    + "try{fetch(u,{credentials:'include',redirect:'manual'});}catch(e){}}}}catch(e){}})();";
                tls.evaluateJavascript(js, null);
            } catch (Exception e) { }
            tlsH.postDelayed(new Runnable() { @Override public void run() { tlsLocalClear(); } }, 2000L);
        } else {
            tlsLocalClear();
        }
    }

    private void tlsLocalClear() {
        try {
            CookieManager cm = CookieManager.getInstance();
            String[] urls = new String[] { TLS_URL, "https://" + TLS_HOST + "/", "https://toyota.astra.co.id/", "https://astra.co.id/" };
            String[] doms = new String[] { "", "; Domain=" + TLS_HOST, "; Domain=.toyota.astra.co.id", "; Domain=.astra.co.id" };
            for (String uu : urls) {
                String ck = cm.getCookie(uu);
                if (ck == null) continue;
                for (String part : ck.split(";")) {
                    String nm = part.trim(); int eq = nm.indexOf('=');
                    if (eq > 0) nm = nm.substring(0, eq);
                    if (nm.length() == 0) continue;
                    for (String d : doms) {
                        cm.setCookie(uu, nm + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/" + d);
                    }
                }
            }
            cm.flush();
            String left = cm.getCookie(TLS_URL);
            if (left != null && left.length() > 0) { cm.removeAllCookies(null); cm.flush(); }   // cadangan bila ada cookie yang tidak bisa dihapus satu per satu
        } catch (Exception e) { }
        try { android.webkit.WebStorage.getInstance().deleteOrigin("https://" + TLS_HOST); } catch (Exception e) { }
        try { tls.clearCache(true); tls.clearHistory(); tls.clearFormData(); } catch (Exception e) { }
        try { tls.loadUrl("about:blank"); } catch (Exception e) { }   // halaman TLS dihentikan agar tidak ada skrip yang menjaga sesi tetap hidup
    }

    private void xlCopyClip() {
        try {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("manifest", xlTsv));
        } catch (Exception e) { }
    }

    /** Tombol Tempel manifest: salin ke clipboard lalu kirim tekan Ctrl+V ke Excel (sel yang sedang dipilih). */
    private void xlPaste() {
        try {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("manifest", xlTsv));
            xl.requestFocus();
            final long t = android.os.SystemClock.uptimeMillis();
            final int meta = android.view.KeyEvent.META_CTRL_ON | android.view.KeyEvent.META_CTRL_LEFT_ON;
            xl.dispatchKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_CTRL_LEFT, 0, android.view.KeyEvent.META_CTRL_ON));
            xl.dispatchKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_V, 0, meta));
            xl.dispatchKeyEvent(new android.view.KeyEvent(t, t + 40, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_V, 0, meta));
            xl.dispatchKeyEvent(new android.view.KeyEvent(t, t + 40, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_CTRL_LEFT, 0, 0));
            Toast.makeText(this, "Perintah tempel dikirim. Kalau sel belum terisi, tahan sel itu lalu pilih Tempel (baris sudah ada di clipboard).", Toast.LENGTH_LONG).show();
            xlDoneBtn.setVisibility(View.VISIBLE);
        } catch (Exception e) { }
    }

    private void showXl() {
        xlDoneBtn.setVisibility(View.GONE);
        radar.setVisibility(View.GONE);
        tls.setVisibility(View.GONE);
        xl.setVisibility(View.VISIBLE);
        backBtn.setVisibility(View.VISIBLE);
        reloadBtn.setVisibility(View.VISIBLE);
        tlsBtn.setVisibility(View.GONE);
        valBtn.setVisibility(View.GONE);
        xlCopyBtn.setVisibility(View.VISIBLE);
    }

    private void showTls() {
        tlsH.removeCallbacks(tlsIdleLogout);
        tlsSessionCheck(true);
        xl.setVisibility(View.GONE);
        xlCopyBtn.setVisibility(View.GONE);
        xlDoneBtn.setVisibility(View.GONE);
        radar.setVisibility(View.GONE);
        tls.setVisibility(View.VISIBLE);
        backBtn.setVisibility(View.VISIBLE);
        reloadBtn.setVisibility(View.VISIBLE);
        tlsBtn.setVisibility(View.GONE);
        valBtn.setVisibility(View.GONE);   // tombol melayang dihilangkan; Validasi selesai ditekan di RADAR lewat tombol \u2190 RADAR
    }

    private void showRadar() {
        try { if (tls.getVisibility() == View.VISIBLE) { tlsTouch(); tlsH.removeCallbacks(tlsIdleLogout); tlsH.postDelayed(tlsIdleLogout, 3 * 60 * 1000L); } } catch (Exception e) { }
        tls.setVisibility(View.GONE);
        xl.setVisibility(View.GONE);
        xlCopyBtn.setVisibility(View.GONE);
        xlDoneBtn.setVisibility(View.GONE);
        backBtn.setVisibility(View.GONE);
        reloadBtn.setVisibility(View.GONE);
        valBtn.setVisibility(View.GONE);
        tlsBtn.setVisibility(View.GONE);
        radar.setVisibility(View.VISIBLE);
    }

    // ---------- ALARM giliran parkir: bunyi + getar berulang sampai alarmStop ----------
    private android.media.MediaPlayer alarmMp;
    private android.os.Vibrator alarmVib;
    private final android.os.Handler alarmH = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable alarmAutoStop = new Runnable() { @Override public void run() { alarmStopNative(); } };

    private void alarmStopNative() {
        alarmH.removeCallbacks(alarmAutoStop);
        try { if (alarmMp != null) { try { alarmMp.stop(); } catch (Exception e) { } alarmMp.release(); } } catch (Exception e) { }
        alarmMp = null;
        try { if (alarmVib != null) alarmVib.cancel(); } catch (Exception e) { }
        alarmVib = null;
    }

    private void alarmStartNative(String title, String body) { alarmStartNative(title, body, 5 * 60 * 1000L); }

    private void alarmStartNative(String title, String body, long maxMs) {
        alarmStopNative();
        notifyNative(title, body);
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            try { am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0); } catch (Exception e) { }
            Uri u = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
            if (u == null) u = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE);
            alarmMp = new android.media.MediaPlayer();
            alarmMp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            alarmMp.setDataSource(this, u);
            alarmMp.setLooping(true);
            alarmMp.prepare();
            alarmMp.start();
        } catch (Exception e) { }
        try {
            alarmVib = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            alarmVib.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 800, 400}, 0));
        } catch (Exception e) { }
        alarmH.postDelayed(alarmAutoStop, maxMs);
    }

    // ---------- login sidik jari ----------
    private javax.crypto.SecretKey bioKey() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias("radar_bio")) return (javax.crypto.SecretKey) ks.getKey("radar_bio", null);
        javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        android.security.keystore.KeyGenParameterSpec.Builder b = new android.security.keystore.KeyGenParameterSpec.Builder("radar_bio",
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true);
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, android.security.keystore.KeyProperties.AUTH_BIOMETRIC_STRONG);
        else b.setUserAuthenticationValidityDurationSeconds(-1);
        kg.init(b.build());
        return kg.generateKey();
    }

    private void bioJs(String js) {
        try { radar.evaluateJavascript(js, null); } catch (Exception e) { }
    }

    private void bioErr(String m) {
        bioJs("if(window.radarBioErr)radarBioErr(" + JSONObject.quote(m) + ");");
    }

    private void bioPrompt(javax.crypto.Cipher c, final boolean enroll, final String user, final String pass) {
        try {
            android.hardware.biometrics.BiometricPrompt bp = new android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("RADAR EKY")
                .setSubtitle(enroll ? "Tempelkan jari untuk mengaktifkan login sidik jari" : "Tempelkan jari untuk masuk")
                .setNegativeButton("Ketik manual", getMainExecutor(), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { bioErr(""); } })
                .build();
            bp.authenticate(new android.hardware.biometrics.BiometricPrompt.CryptoObject(c), new android.os.CancellationSignal(), getMainExecutor(),
                new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                        try {
                            javax.crypto.Cipher ci = r.getCryptoObject().getCipher();
                            SharedPreferences sp = getSharedPreferences("radar", MODE_PRIVATE);
                            if (enroll) {
                                JSONObject o = new JSONObject(); o.put("u", user); o.put("p", pass);
                                byte[] ct = ci.doFinal(o.toString().getBytes("UTF-8"));
                                sp.edit().putString("bioCt", Base64.encodeToString(ct, Base64.NO_WRAP))
                                    .putString("bioIv", Base64.encodeToString(ci.getIV(), Base64.NO_WRAP))
                                    .putString("bioUser", user).apply();
                                bioJs("if(window.radarBioEnrolled)radarBioEnrolled();");
                            } else {
                                byte[] pt = ci.doFinal(Base64.decode(sp.getString("bioCt", ""), Base64.NO_WRAP));
                                JSONObject o = new JSONObject(new String(pt, "UTF-8"));
                                bioJs("if(window.radarBioResult)radarBioResult(" + JSONObject.quote(o.getString("u")) + "," + JSONObject.quote(o.getString("p")) + ");");
                            }
                        } catch (Exception e) { bioErr("Sidik jari gagal dipakai: " + e.getMessage()); }
                    }
                    @Override public void onAuthenticationError(int code, CharSequence msg) {
                        if (code == 5 || code == 10) bioErr("");   // 5 = dibatalkan, 10 = pengguna membatalkan
                        else bioErr(String.valueOf(msg));
                    }
                });
        } catch (Exception e) { bioErr("Sidik jari tidak bisa dipakai: " + e.getMessage()); }
    }

    private void bioEnrollUi(String user, String pass) {
        try {
            sp_bioClear();   // selalu buat kunci baru supaya sidik jari yang berubah tidak menghalangi pendaftaran ulang
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, bioKey());
            bioPrompt(c, true, user, pass);
        } catch (Exception e) { bioErr("Sidik jari belum bisa diaktifkan: " + e.getMessage()); }
    }

    private void bioLoginUi() {
        try {
            SharedPreferences sp = getSharedPreferences("radar", MODE_PRIVATE);
            byte[] iv = Base64.decode(sp.getString("bioIv", ""), Base64.NO_WRAP);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, bioKey(), new javax.crypto.spec.GCMParameterSpec(128, iv));
            bioPrompt(c, false, null, null);
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
            sp_bioClear();
            bioErr("Data sidik jari di HP berubah. Login ketik sekali lagi, lalu aktifkan sidik jari lagi.");
        } catch (Exception e) { bioErr("Sidik jari belum bisa dipakai: " + e.getMessage()); }
    }

    private void sp_bioClear() {
        try { getSharedPreferences("radar", MODE_PRIVATE).edit().remove("bioCt").remove("bioIv").remove("bioUser").apply(); } catch (Exception e) { }
        try { java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry("radar_bio"); } catch (Exception e) { }
    }

    private void notifyNative(String title, String body) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("radar2", "Peringatan RADAR", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = new Notification.Builder(this, "radar2");
            b.setContentTitle(title);
            b.setContentText(body);
            b.setSmallIcon(android.R.drawable.ic_dialog_alert);
            b.setAutoCancel(true);
            b.setContentIntent(pi);
            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), b.build());
        } catch (Exception e) { }
    }

    // ---------- GPS: minta izin + ajak aktifkan GPS saat aplikasi dibuka ----------
    private void checkGps() {
        if (gpsDlg) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            boolean on = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (on) return;
            gpsDlg = true;
            new AlertDialog.Builder(this).setTitle("GPS belum aktif")
                .setMessage("RADAR memerlukan GPS untuk absen kedatangan. Aktifkan lokasi sekarang?")
                .setCancelable(false)
                .setPositiveButton("Aktifkan GPS", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        gpsDlg = false;
                        try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); } catch (Exception e) { }
                    } })
                .setNegativeButton("Nanti", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) { gpsDlg = false; }
                }).show();
        } catch (Exception e) { gpsDlg = false; }
    }

    @Override
    protected void onDestroy() {
        if (inst == this) inst = null;
        super.onDestroy();
    }

    private void askBatteryOnce() {
        if (true) return;   // diganti panduan izin (permWizard)
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            final SharedPreferences sp = getSharedPreferences("radar", MODE_PRIVATE);
            if (sp.getBoolean("battAsked", false)) return;
            sp.edit().putBoolean("battAsked", true).apply();
            new AlertDialog.Builder(this).setTitle("Agar absen tetap jalan")
                .setMessage("Supaya absen otomatis tetap berjalan saat layar terkunci, izinkan RADAR berjalan tanpa pembatasan baterai. Pilih Izinkan di layar berikutnya.")
                .setPositiveButton("Lanjut", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            Intent it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            it.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(it);
                        } catch (Exception e) { }
                    } })
                .setNegativeButton("Nanti", null).show();
        } catch (Exception e) { }
    }

    @Override
    protected void onPause() {
        super.onPause();
        fg = false;
        try { radar.resumeTimers(); } catch (Exception e) { }   // JS RADAR tetap berjalan saat layar mati / aplikasi lain dibuka
        try { tlsTouch(); tlsH.removeCallbacks(tlsIdleLogout); tlsH.postDelayed(tlsIdleLogout, 3 * 60 * 1000L); } catch (Exception e) { }   // layar mati / app ke latar: 3 menit lagi halaman TLS dihentikan (seperti tab ditutup), tanpa perlu dibuka
    }

    @Override
    protected void onResume() {
        super.onResume();
        fg = true;
        try {
            tlsH.removeCallbacks(tlsIdleLogout);
            if (tls != null && tls.getVisibility() == View.VISIBLE) { String cu = tls.getUrl(); if (cu == null || cu.startsWith("about:")) tls.loadUrl(TLS_URL); }
            else tlsSessionCheck(false);
        } catch (Exception e) { }
        alarmStopNative();   // dering mati begitu aplikasi dibuka
        checkGps();
        if (!updRunning && inst == this) checkApkUpdate();
        permWizard();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != 11) return;
        boolean fine = false;
        for (int i = 0; i < perms.length; i++) {
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(perms[i]) && res[i] == PackageManager.PERMISSION_GRANTED) fine = true;
        }
        if (!fine) {
            new AlertDialog.Builder(this).setTitle("Izin lokasi diperlukan")
                .setMessage("Tanpa izin lokasi, absen kedatangan tidak bisa berjalan. Buka Pengaturan lalu pilih Izin > Lokasi > Izinkan.")
                .setPositiveButton("Buka Pengaturan", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            Intent it = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            it.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(it);
                        } catch (Exception e) { }
                    } })
                .setNegativeButton("Nanti", null).show();
        } else {
            checkGps();
        }
    }

    class SjBridge {
        @JavascriptInterface
        public void probe(String j) {
            try {
                final JSONObject o = new JSONObject(j);
                final String e = o.optString("e", "");
                final String u = o.optString("u", "");
                if (e.length() > 0 && !e.startsWith("about:")) {
                    if (e.startsWith("data:")) {
                        int ci = e.indexOf(',');
                        if (ci > 0 && e.length() < 4200000) pdf("ILS.pdf", e.substring(ci + 1).replace("%2B", "+").replace("%2F", "/").replace("%3D", "="));
                        return;
                    }
                    if (e.startsWith("blob:")) {
                        final String js = "(function(){fetch(" + JSONObject.quote(e) + ").then(function(r){return r.blob();}).then(function(b){var f=new FileReader();f.onload=function(){var s=String(f.result);AndroidSJ.pdf('ILS.pdf',s.substring(s.indexOf(',')+1));};f.readAsDataURL(b);});})();";
                        runOnUiThread(new Runnable() { @Override public void run() { if (pop != null) pop.evaluateJavascript(js, null); } });
                    } else {
                        fetchPdfNative(e, pop != null ? pop.getSettings().getUserAgentString() : "RADAR", "ILS.pdf");
                    }
                    return;
                }
                if (o.optInt("p", 0) == 0 && o.optInt("l", 0) < 5 && popPdfData == null && !u.startsWith("about:")) {
                    final String m = "Halaman kosong. URL: " + u + " | jenis: " + o.optString("t", "?") + " | isi: " + o.optInt("h", 0);
                    runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show(); } });
                }
            } catch (Exception ex) { }
        }

        @JavascriptInterface
        public void pdf(String name, String b64) {
            if (b64 == null || b64.length() > 4200000) return;
            long nowT = System.currentTimeMillis();
            int hc = b64.length() * 31 + b64.hashCode();
            if (hc == lastPdfHash && nowT - lastPdfAt < 8000) return;
            lastPdfHash = hc; lastPdfAt = nowT;
            String nm = name == null ? "surat-jalan.pdf" : name;
            if (sjId != null) { deliverPdf(nm, b64); return; }
            runOnUiThread(new Runnable() { @Override public void run() {
                Toast.makeText(MainActivity.this, "PDF ini belum dikaitkan ke surat jalan. Tekan tombol Jadikan surat jalan untuk menyimpannya.", Toast.LENGTH_LONG).show(); } });
            try { showPdfViewer(nm, Base64.decode(b64, Base64.DEFAULT)); } catch (Exception e) { }
        }
    }

    class Bridge {
        @JavascriptInterface
        public void send(final String pkg) {
            if (pkg == null || !pkg.startsWith("RADAR-TLS:") || pkg.length() > 200000) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    pendingPkg = pkg;
                    tlsRedirects = 0;
                    showTls();
                    tls.loadUrl(TLS_URL);
                    Toast.makeText(MainActivity.this, "Membuka TLS... isian terisi otomatis setelah halaman siap", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String safe = (name == null || name.length() == 0) ? "radar-file" : name;
                saveBytes(name, mime, data);
                final String msg = "Tersimpan di folder Download: " + safe;
                runOnUiThread(new Runnable() {
                    @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
                });
            } catch (Exception e) {
                final String em = "Gagal menyimpan file: " + e.getMessage();
                runOnUiThread(new Runnable() {
                    @Override public void run() { Toast.makeText(MainActivity.this, em, Toast.LENGTH_LONG).show(); }
                });
            }
        }

        @JavascriptInterface
        public void openValidasi(final String id, final String url, final String plat) {
            if (id == null || !id.matches("[0-9]{1,20}")) return;
            String t = "https://" + TLS_HOST + "/";
            if (url != null && url.startsWith("https://")) {
                String h = Uri.parse(url).getHost();
                if (h != null && h.endsWith("astra.co.id")) t = url;
            }
            final String target = t;
            runOnUiThread(new Runnable() { @Override public void run() {
                sjId = id;
                showTls();
                tls.loadUrl(target);
                Toast.makeText(MainActivity.this, "Validasi: pindai QR tiap unit, tekan Generate, lalu kembali ke RADAR (tombol \u2190 RADAR) dan tekan Validasi selesai.", Toast.LENGTH_LONG).show();
            } });
        }

        @JavascriptInterface
        public void bgStart() {
            runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
                    Intent si = new Intent(MainActivity.this, LocationService.class);
                    si.putExtra("loc", true);
                    startForegroundService(si);
                    askBatteryOnce();
                } catch (Exception e) { }
            } });
        }

        @JavascriptInterface
        public void trkConfig(String key, String plat, String lp) {
            try {
                getSharedPreferences("radar", MODE_PRIVATE).edit()
                    .putString("trkKey", key == null ? "" : key)
                    .putString("trkPlat", plat == null ? "" : plat)
                    .putString("trkLp", lp == null ? "" : lp)
                    .putLong("trkStartTs", System.currentTimeMillis())
                    .putString("trkLast", "").apply();
            } catch (Exception e) { }
        }

        @JavascriptInterface
        public void trkUser(String u) {
            try { getSharedPreferences("radar", MODE_PRIVATE).edit().putString("trkUser", u == null ? "" : u).apply(); } catch (Exception e) { }
        }

        @JavascriptInterface
        public String trkStatus() {
            try { return getSharedPreferences("radar", MODE_PRIVATE).getString("trkLast", ""); } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void bgStop() {
            runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    if (LocationService.running) {
                        Intent si = new Intent(MainActivity.this, LocationService.class);
                        si.putExtra("stoploc", true);
                        startService(si);
                    }
                } catch (Exception e) { }
            } });
        }

        @JavascriptInterface
        public void keepAlive() {
            runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
                    Intent si = new Intent(MainActivity.this, LocationService.class);
                    startForegroundService(si);
                    askBatteryOnce();
                } catch (Exception e) { }
            } });
        }

        // ===== Login sidik jari (v60): user + password disimpan terenkripsi (Android Keystore), hanya terbuka dengan sidik jari =====
        @JavascriptInterface
        public boolean bioAvail() {
            try {
                android.hardware.biometrics.BiometricManager bm = (android.hardware.biometrics.BiometricManager) getSystemService(Context.BIOMETRIC_SERVICE);
                return bm != null && bm.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean bioHas() {
            try { return getSharedPreferences("radar", MODE_PRIVATE).getString("bioCt", "").length() > 0; } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public String bioUser() {
            try { return getSharedPreferences("radar", MODE_PRIVATE).getString("bioUser", ""); } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void bioForget() {
            try { getSharedPreferences("radar", MODE_PRIVATE).edit().remove("bioCt").remove("bioIv").remove("bioUser").apply(); } catch (Exception e) { }
            try { java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry("radar_bio"); } catch (Exception e) { }
        }

        @JavascriptInterface
        public void bioEnroll(final String user, final String pass) {
            runOnUiThread(new Runnable() { @Override public void run() { bioEnrollUi(user, pass); } });
        }

        @JavascriptInterface
        public void bioLogin() {
            runOnUiThread(new Runnable() { @Override public void run() { bioLoginUi(); } });
        }

        @JavascriptInterface
        public void keepStop() {
            runOnUiThread(new Runnable() { @Override public void run() {
                try { stopService(new Intent(MainActivity.this, LocationService.class)); } catch (Exception e) { }
            } });
        }

        @JavascriptInterface
        public void minimizeApp() {
            runOnUiThread(new Runnable() { @Override public void run() { moveTaskToBack(true); } });
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(new Runnable() { @Override public void run() {
                try { stopService(new Intent(MainActivity.this, LocationService.class)); } catch (Exception e) { }
                finishAffinity();
            } });
        }

        @JavascriptInterface
        public void openTls() {
            runOnUiThread(new Runnable() { @Override public void run() {
                showTls();
                { String cu = tls.getUrl(); if (cu == null || cu.startsWith("about:")) tls.loadUrl(TLS_URL); }
            } });
        }

        @JavascriptInterface
        public void logoutTls() {
            runOnUiThread(new Runnable() { @Override public void run() { tlsLogout(true); } });
        }

        @JavascriptInterface
        public void closeTls() {
            runOnUiThread(new Runnable() { @Override public void run() { sjId = null; showRadar(); } });
        }

        @JavascriptInterface
        public void openPdf(final String name, final String b64) {
            try {
                Uri uri = saveBytes(name, "application/pdf", Base64.decode(b64, Base64.DEFAULT));
                Intent it = new Intent(Intent.ACTION_VIEW);
                it.setDataAndType(uri, "application/pdf");
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(Intent.createChooser(it, "Buka surat jalan"));
            } catch (final Exception e) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    Toast.makeText(MainActivity.this, "Tidak bisa membuka PDF: " + e.getMessage(), Toast.LENGTH_LONG).show(); } });
            }
        }

        // Kirim PDF lewat WhatsApp (ACTION_SEND). WhatsApp tidak muncul di daftar "Buka dengan" karena itu bukan aplikasi pembaca PDF.
        @JavascriptInterface
        public void sharePdf(final String name, final String b64, final String text) {
            try {
                Uri uri = saveBytes(name, "application/pdf", Base64.decode(b64, Base64.DEFAULT));
                Intent it = new Intent(Intent.ACTION_SEND);
                it.setType("application/pdf");
                it.putExtra(Intent.EXTRA_STREAM, uri);
                if (text != null && text.length() > 0) it.putExtra(Intent.EXTRA_TEXT, text);
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                String[] wa = new String[]{"com.whatsapp", "com.whatsapp.w4b"};
                for (int i = 0; i < wa.length; i++) {
                    try { Intent w = new Intent(it); w.setPackage(wa[i]); startActivity(w); return; } catch (Exception ignore) { }
                }
                Intent ch = Intent.createChooser(it, "Kirim surat jalan");
                ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(ch);
            } catch (final Exception e) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    Toast.makeText(MainActivity.this, "Tidak bisa membagikan PDF: " + e.getMessage(), Toast.LENGTH_LONG).show(); } });
            }
        }

        @JavascriptInterface
        public void alarmStart(final String title, final String body) {
            runOnUiThread(new Runnable() {
                @Override public void run() { alarmStartNative(title, body); }
            });
        }

        @JavascriptInterface
        public void alarmStop() {
            runOnUiThread(new Runnable() {
                @Override public void run() { alarmStopNative(); }
            });
        }

        @JavascriptInterface
        public boolean isFg() { return fg; }

        /** Buka aplikasi email dengan penerima, subjek, dan isi sudah terisi (untuk flow Power Automate). */
        @JavascriptInterface
        public void sendMail(final String to, final String subject, final String body) {
            runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    Intent it = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
                    it.putExtra(Intent.EXTRA_EMAIL, new String[] { to == null ? "" : to });
                    it.putExtra(Intent.EXTRA_SUBJECT, subject == null ? "" : subject);
                    it.putExtra(Intent.EXTRA_TEXT, body == null ? "" : body);
                    startActivity(Intent.createChooser(it, "Kirim manifest lewat email"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Tidak ada aplikasi email di HP ini.", Toast.LENGTH_LONG).show();
                }
            } });
        }

        /** Buka Excel Online (manifest) di browser dalam aplikasi; baris manifest disalin ke clipboard lebih dulu. */
        @JavascriptInterface
        public void openExcel(final String url, final String tsv) {
            runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    Uri u = Uri.parse(url == null ? "" : url);
                    String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
                    boolean okHost = "https".equals(u.getScheme()) && (h.endsWith("sharepoint.com") || h.endsWith("office.com") || h.endsWith("office365.com")
                        || h.endsWith("microsoftonline.com") || h.endsWith("live.com") || h.endsWith("1drv.ms") || h.endsWith("microsoft.com"));
                    if (!okHost) { Toast.makeText(MainActivity.this, "Link Excel tidak dikenali (harus link SharePoint / OneDrive).", Toast.LENGTH_LONG).show(); return; }
                    xlTsv = tsv == null ? "" : tsv;
                    showXl();
                    if (xlTsv.length() > 0) { xlCopyClip(); Toast.makeText(MainActivity.this, "Ketuk sel kosong pertama di bawah tabel Manifest, lalu tekan Tempel manifest.", Toast.LENGTH_LONG).show(); }
                    else Toast.makeText(MainActivity.this, "Tidak ada baris manifest untuk pilihan ini.", Toast.LENGTH_LONG).show();
                    if (xl.getUrl() == null || !url.equals(xlLastUrl)) { xlLastUrl = url; xl.loadUrl(url); }
                } catch (Exception e) { }
            } });
        }

        // Buka TLS di Chrome (login TAM Passport lewat browser). Hanya https ke host TLS.
        @JavascriptInterface
        public boolean openExternal(final String url) {
            try {
                Uri u = Uri.parse(url == null ? "" : url);
                if (!"https".equals(u.getScheme()) || u.getHost() == null || !u.getHost().endsWith("toyota.astra.co.id")) return false;
                final Uri uu = u;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Intent it = new Intent(Intent.ACTION_VIEW, uu);
                        try { it.setPackage("com.android.chrome"); startActivity(it); }
                        catch (Exception e1) { try { startActivity(new Intent(Intent.ACTION_VIEW, uu)); } catch (Exception e2) { } }
                    }
                });
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void notify(final String title, final String body) {
            runOnUiThread(new Runnable() {
                @Override public void run() { alarmStartNative(title, body, fg ? 6000L : 60000L); }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (popRoot.getVisibility() == View.VISIBLE) {
            closePopup();
        } else if (xl.getVisibility() == View.VISIBLE) {
            if (xl.canGoBack()) xl.goBack(); else showRadar();
        } else if (tls.getVisibility() == View.VISIBLE) {
            showRadar();
        } else if (radar.canGoBack()) {
            radar.goBack();
        } else {
            moveTaskToBack(true);   // tombol Kembali hanya meminimalkan; keluar lewat tombol Keluar Aplikasi di layar login
        }
    }
}
