package id.eky.radar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.PowerManager;

public class LocationService extends Service implements LocationListener {
    private LocationManager lm;
    private PowerManager.WakeLock wl;
    private boolean started = false;

    @Override public IBinder onBind(Intent i) { return null; }

    static volatile boolean running = false;
    private boolean locOn = false;

    private final Handler hd = new Handler(Looper.getMainLooper());
    private long lastFix = 0;

    // Penjaga berkala (tiap 60 dtk): pastikan pembaruan GPS tetap hidup dan wake lock tetap dipegang
    private final Runnable tick = new Runnable() { @Override public void run() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("radar", MODE_PRIVATE);
            boolean want = sp.getBoolean("locOn", false);
            long now = System.currentTimeMillis();
            if (want && lm != null && (!locOn || now - lastFix > 120000)) { stopLocUpdates(); startLocUpdates(); }
            if (wl != null && !wl.isHeld()) wl.acquire();
            killCheck(sp);
        } catch (Exception e) { }
        hd.postDelayed(this, 60000);
    } };

    private void startLocUpdates() {
        try {
            if (lm == null) return;
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 0, this);
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15000, 50, this);
            locOn = true;
            lastFix = System.currentTimeMillis();
        } catch (Exception e) { }
    }

    @Override public void onCreate() { super.onCreate(); running = true; }

    // Aplikasi digeser dari menu Recent: minta HP menghidupkan layanan lagi (upaya terbaik; sebagian HP mengizinkan)
    @Override public void onTaskRemoved(Intent rootIntent) {
        try {
            if (getSharedPreferences("radar", MODE_PRIVATE).getBoolean("locOn", false)) {
                android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
                PendingIntent pi = PendingIntent.getForegroundService(this, 2, new Intent(this, LocationService.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                am.set(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pi);
            }
        } catch (Exception e) { }
        super.onTaskRemoved(rootIntent);
    }

    private void stopLocUpdates() {
        try { if (lm != null) lm.removeUpdates(this); } catch (Exception e) { }
        locOn = false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("radar", MODE_PRIVATE);
            boolean wantLoc;
            if (intent != null && intent.hasExtra("loc")) { wantLoc = intent.getBooleanExtra("loc", false); sp.edit().putBoolean("locOn", wantLoc).apply(); }
            else wantLoc = sp.getBoolean("locOn", false);
            if (intent != null && intent.getBooleanExtra("stoploc", false)) { wantLoc = false; sp.edit().putBoolean("locOn", false).apply(); stopLocUpdates(); }
            if (!started) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new NotificationChannel("radar_bg", "RADAR berjalan di latar belakang", NotificationManager.IMPORTANCE_LOW));
                Intent it = new Intent(this, MainActivity.class);
                it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pi = PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE);
                Notification n = new Notification.Builder(this, "radar_bg")
                    .setContentTitle("RADAR aktif")
                    .setContentText("Berjalan di latar belakang agar tetap online. Ketuk untuk membuka.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .build();
                if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                else startForeground(1, n);
                lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "radar:loc");
                wl.acquire();
                started = true;
                hd.postDelayed(tick, 60000);
            }
            if (wantLoc && !locOn && lm != null) startLocUpdates();
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onLocationChanged(Location l) {
        lastFix = System.currentTimeMillis();
        MainActivity.feed(l.getLatitude(), l.getLongitude(), l.getAccuracy());
        uploadPos(l);
    }

    // ===== Kirim posisi langsung ke Firebase dari layanan latar belakang (tetap jalan walau halaman RADAR dibekukan) =====
    private static final String FB_API_KEY = "AIzaSyAkLYZ6voYE2rpH6F5feRV3MJf1r8bC6YA";
    private static final String FB_DB_URL = "https://radar-tam-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static String idToken = "";
    private static long idExp = 0;
    private static final Object LOCK = new Object();
    private long lastUp = 0;

    private String httpReq(String method, String url, String body, String ctype) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setRequestProperty("Referer", "https://khairulhudatoyota-lgtm.github.io/radar/");
        c.setRequestProperty("Origin", "https://khairulhudatoyota-lgtm.github.io");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", ctype);
            java.io.OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        int code = c.getResponseCode();
        java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[2048]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close(); }
        String out = bo.toString("UTF-8");
        if (code >= 400) throw new Exception("HTTP " + code + " " + (out.length() > 120 ? out.substring(0, 120) : out));
        return out;
    }

    private String fbToken(android.content.SharedPreferences sp) throws Exception {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (idToken.length() > 0 && now < idExp - 60000) return idToken;
            String rt = sp.getString("fbRefresh", "");
            if (rt.length() > 0) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(httpReq("POST", "https://securetoken.googleapis.com/v1/token?key=" + FB_API_KEY,
                        "grant_type=refresh_token&refresh_token=" + java.net.URLEncoder.encode(rt, "UTF-8"), "application/x-www-form-urlencoded"));
                    idToken = o.getString("id_token");
                    idExp = now + Long.parseLong(o.getString("expires_in")) * 1000L;
                    sp.edit().putString("fbRefresh", o.optString("refresh_token", rt)).apply();
                    return idToken;
                } catch (Exception e) { sp.edit().remove("fbRefresh").apply(); }
            }
            org.json.JSONObject o = new org.json.JSONObject(httpReq("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + FB_API_KEY,
                "{\"returnSecureToken\":true}", "application/json"));
            idToken = o.getString("idToken");
            idExp = now + Long.parseLong(o.getString("expiresIn")) * 1000L;
            sp.edit().putString("fbRefresh", o.getString("refreshToken")).apply();
            return idToken;
        }
    }


    /** Perintah "hentikan bagikan posisi" dari Admin (titik tanpa akun): kalau Admin menekan Hapus titik untuk plat ini SESUDAH
     *  pembagian posisi dimulai, layanan ini berhenti sendiri sehingga titik tidak muncul lagi. */
    private void killCheck(final android.content.SharedPreferences sp) {
        final String key = sp.getString("trkKey", "");
        if (key.length() == 0 || !sp.getBoolean("locOn", false)) return;
        new Thread(new Runnable() { @Override public void run() {
            try {
                String tk = fbToken(sp);
                String r = httpReq("GET", FB_DB_URL + "/radar/trkKill/" + java.net.URLEncoder.encode(key, "UTF-8") + ".json?auth=" + tk, null, null);
                if (r == null) return; r = r.trim();
                if (r.length() == 0 || r.equals("null")) return;
                long kv = (long) Double.parseDouble(r);
                if (kv > sp.getLong("trkStartTs", 0)) {
                    sp.edit().putBoolean("locOn", false).putString("trkKey", "").putString("trkLast", "Dihentikan Admin").apply();
                    hd.post(new Runnable() { @Override public void run() { try { stopLocUpdates(); } catch (Exception e) { } try { stopSelf(); } catch (Exception e) { } } });
                }
            } catch (Exception e) { }
        } }).start();
    }

    private void uploadPos(final Location l) {
        final android.content.SharedPreferences sp = getSharedPreferences("radar", MODE_PRIVATE);
        final String key = sp.getString("trkKey", "");
        if (key.length() == 0) return;
        final long now = System.currentTimeMillis();
        if (now - lastUp < 12000) return;
        lastUp = now;
        final String plat = sp.getString("trkPlat", "");
        final String lp = sp.getString("trkLp", "");
        final String usr = sp.getString("trkUser", "");
        new Thread(new Runnable() { @Override public void run() {
            try {
                String tk = fbToken(sp);
                org.json.JSONObject r = new org.json.JSONObject();
                r.put("plat", plat);
                r.put("lp", lp);
                if (usr.length() > 0) r.put("u", usr);
                r.put("lat", Math.round(l.getLatitude() * 1e6) / 1e6);
                r.put("lng", Math.round(l.getLongitude() * 1e6) / 1e6);
                r.put("acc", Math.round(l.getAccuracy()));
                r.put("spd", Math.round(l.getSpeed() * 3.6));
                r.put("ts", now);
                httpReq("PUT", FB_DB_URL + "/radar_track/" + java.net.URLEncoder.encode(key, "UTF-8") + ".json?auth=" + tk, r.toString(), "application/json");
                sp.edit().putString("trkLast", "OK " + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(now))).apply();
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                if (m.indexOf("HTTP 401") >= 0 || m.indexOf("HTTP 403") >= 0) idToken = "";
                sp.edit().putString("trkLast", "GAGAL " + (m.length() > 140 ? m.substring(0, 140) : m)).apply();
            }
        } }).start();
    }
    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) {
        try { if (getSharedPreferences("radar", MODE_PRIVATE).getBoolean("locOn", false)) { stopLocUpdates(); startLocUpdates(); } } catch (Exception e) { }
    }
    @Override public void onProviderDisabled(String p) { }

    @Override
    public void onDestroy() {
        running = false;
        try { hd.removeCallbacks(tick); } catch (Exception e) { }
        try { if (lm != null) lm.removeUpdates(this); } catch (Exception e) { }
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception e) { }
        super.onDestroy();
    }
}
