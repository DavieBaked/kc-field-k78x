package io.github.daviebaked.kruger;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The few phone things the checklist needs that a web page can't do on its own. */
@CapacitorPlugin(name = "KcNative")
public class KcNativePlugin extends Plugin {
    private static KcNativePlugin self;
    // a KML shared into the app, waiting for the page to pick it up
    private static String sharedName, sharedB64;
    private static boolean sharedNone;
    private static final String MSG_CHANNEL = "messages";
    private static final String SHARE_DONE = "io.github.daviebaked.kruger.SHARE_CHOSEN";

    private PluginCall shareCall;
    private boolean shareChosen, shareOut;
    private BroadcastReceiver shareRx;

    @Override
    public void load() {
        self = this;
        shareRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                shareChosen = true;
                finishShare(false);
            }
        };
        ContextCompat.registerReceiver(getContext(), shareRx, new IntentFilter(SHARE_DONE), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void handleOnDestroy() {
        try { getContext().unregisterReceiver(shareRx); } catch (Exception ignored) {}
        if (self == this) self = null;
    }

    @Override
    protected void handleOnResume() {
        JSObject o = new JSObject(); o.put("active", true);
        notifyListeners("appState", o);
        // back from the share sheet without picking anything: cancelled
        if (shareCall != null && shareOut) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> { if (shareCall != null && !shareChosen) finishShare(true); }, 700);
        }
    }

    @Override
    protected void handleOnPause() {
        JSObject o = new JSObject(); o.put("active", false);
        notifyListeners("appState", o);
        if (shareCall != null) shareOut = true;
    }

    /* ---------- the KML share target ---------- */
    static void takeIntent(Activity a, Intent intent) {
        if (intent == null) return;
        String act = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(act)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(act)) {
            ArrayList<Uri> l = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        } else if (Intent.ACTION_VIEW.equals(act) && intent.getData() != null) {
            uris.add(intent.getData());
        } else return;
        String bestName = null; byte[] best = null; int bestScore = 0;
        for (Uri u : uris) {
            try {
                String name = nameOf(a, u);
                byte[] data = readAll(a, u, 30 * 1024 * 1024);
                if (data == null) continue;
                int score = 0;
                if (name != null && name.toLowerCase().matches(".*\\.km[lz]$")) score += 3;
                int i = 0;
                if (data.length > 3 && (data[0] & 0xff) == 0xef && (data[1] & 0xff) == 0xbb) i = 3;
                while (i < data.length && i < 64 && Character.isWhitespace(data[i])) i++;
                if (i < data.length && data[i] == '<') score += 2;
                if (data.length > 1 && data[0] == 'P' && data[1] == 'K') score += 2;
                if (data.length > 1 && ((data[0] & 0xff) == 0xff || (data[0] & 0xff) == 0xfe)) score += 2;
                if (score > bestScore) { bestScore = score; best = data; bestName = name; }
            } catch (Exception ignored) {}
        }
        intent.setAction("");   // handled: don't load it again on rotation
        if (best != null && bestScore >= 2) {
            sharedName = bestName != null ? bestName : "shared.kml";
            sharedB64 = Base64.encodeToString(best, Base64.NO_WRAP);
            sharedNone = false;
        } else {
            sharedName = null; sharedB64 = null; sharedNone = true;
        }
        if (self != null) self.notifyListeners("shared", new JSObject(), true);
    }

    private static String nameOf(Context c, Uri u) {
        String n = null;
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) n = cur.getString(0);
        } catch (Exception ignored) {}
        if (n == null) n = u.getLastPathSegment();
        return n;
    }

    private static byte[] readAll(Context c, Uri u, int max) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int n, total = 0;
            while ((n = in.read(buf)) > 0) { total += n; if (total > max) return null; out.write(buf, 0, n); }
            return out.toByteArray();
        }
    }

    @PluginMethod
    public void getShared(PluginCall call) {
        JSObject o = new JSObject();
        if (sharedB64 != null) { o.put("name", sharedName); o.put("base64", sharedB64); }
        else if (sharedNone) o.put("none", true);
        sharedName = null; sharedB64 = null; sharedNone = false;
        call.resolve(o);
    }

    /* ---------- LoRa status on the lock screen (foreground service) ---------- */
    @PluginMethod
    public void status(PluginCall call) {
        StatusService.title = call.getString("title", "LoRa");
        StatusService.body = call.getString("body", "");
        Context c = getContext();
        if (StatusService.running) {
            try { NotificationManagerCompat.from(c).notify(StatusService.ID, StatusService.build(c)); } catch (SecurityException ignored) {}
        } else {
            try { ContextCompat.startForegroundService(c, new Intent(c, StatusService.class)); }
            catch (Exception e) {
                try { NotificationManagerCompat.from(c).notify(StatusService.ID, StatusService.build(c)); } catch (SecurityException ignored) {}
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void stopStatus(PluginCall call) {
        Context c = getContext();
        c.stopService(new Intent(c, StatusService.class));
        NotificationManagerCompat.from(c).cancel(StatusService.ID);
        call.resolve();
    }

    /* ---------- a new message ---------- */
    @PluginMethod
    public void notify(PluginCall call) {
        Context c = getContext();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(MSG_CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(MSG_CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("New LoRa messages");
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
        }
        String thread = call.getString("thread", "all");
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification n = new NotificationCompat.Builder(c, MSG_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_lora)
                .setContentTitle(call.getString("title", "Message"))
                .setContentText(call.getString("body", ""))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(call.getString("body", "")))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        try { NotificationManagerCompat.from(c).notify(5000 + (thread.hashCode() & 0xfff), n); } catch (SecurityException ignored) {}
        call.resolve();
    }

    /* ---------- keep the screen on ---------- */
    @PluginMethod
    public void keepAwake(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        getActivity().runOnUiThread(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            call.resolve();
        });
    }

    /* ---------- share sheet (text and files) ---------- */
    @PluginMethod
    public void share(PluginCall call) {
        try {
            Context c = getContext();
            File dir = new File(c.getCacheDir(), "share");
            dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            ArrayList<Uri> uris = new ArrayList<>();
            String mime = null;
            JSArray files = call.getArray("files", new JSArray());
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                String name = f.optString("name", "file").replaceAll("[\\\\/:*?\"<>|]", "_");
                File out = new File(dir, name);
                try (FileOutputStream fo = new FileOutputStream(out)) { fo.write(Base64.decode(f.optString("base64", ""), Base64.DEFAULT)); }
                uris.add(FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", out));
                String m = f.optString("mime", "application/octet-stream");
                mime = mime == null || mime.equals(m) ? m : "*/*";
            }
            String text = call.getString("text", "");
            Intent send;
            if (uris.size() > 1) {
                send = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType(mime);
            } else if (uris.size() == 1) {
                send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0)).setType(mime);
                send.setClipData(ClipData.newRawUri("", uris.get(0)));
            } else {
                send = new Intent(Intent.ACTION_SEND).setType("text/plain");
            }
            if (text != null && !text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent done = new Intent(SHARE_DONE).setPackage(c.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(c, 2, done,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            Intent chooser = Intent.createChooser(send, call.getString("title", null), pi.getIntentSender());
            if (shareCall != null) finishShare(true);
            shareCall = call; shareChosen = false; shareOut = false;
            call.setKeepAlive(true);
            getActivity().startActivity(chooser);
        } catch (Exception e) {
            call.reject("Couldn't share: " + e.getMessage());
        }
    }

    private void finishShare(boolean cancelled) {
        PluginCall c = shareCall;
        if (c == null) return;
        shareCall = null; shareOut = false;
        JSObject o = new JSObject(); o.put("cancelled", cancelled);
        c.resolve(o);
        c.setKeepAlive(false);
        bridge.releaseCall(c);
    }

    /* ---------- clipboard, links, version ---------- */
    @PluginMethod
    public void copy(PluginCall call) {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Kruger", call.getString("text", "")));
        call.resolve();
    }

    @PluginMethod
    public void paste(PluginCall call) {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        JSObject o = new JSObject();
        ClipData d = cm.getPrimaryClip();
        o.put("text", d != null && d.getItemCount() > 0 ? String.valueOf(d.getItemAt(0).coerceToText(getContext())) : "");
        call.resolve(o);
    }

    @PluginMethod
    public void openUrl(PluginCall call) {
        try {
            getActivity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(call.getString("url", ""))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage()); }
    }

    @PluginMethod
    public void info(PluginCall call) {
        JSObject o = new JSObject();
        try {
            PackageInfo p = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            o.put("versionName", p.versionName);
            o.put("versionCode", Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode);
        } catch (Exception ignored) {}
        call.resolve(o);
    }
}
