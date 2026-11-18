package com.leander.catisland;

import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.TextView;
import java.io.*;
import java.net.*;
import org.json.JSONObject;

public class IslandService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private CatPill pill;
    private boolean expanded, busy;
    private String message = "", label = "";
    private static final long MESSAGE_DURATION_MS = 2 * 60 * 1000L;
    private volatile boolean running;
    private volatile HttpURLConnection connection;
    private Thread receiver;
    private WindowManager.LayoutParams overlayParams;
    private float downX,downY;
    private int originX,originY;
    private boolean dragging;
    private long messageDeadline;
    private boolean musicPlaying;
    private boolean watchingDouyin;
    private long usageCursor, studyUntil, lastStudyReminder;
    private String foregroundPackage = "";
    private void checkStudy() {
        android.app.AppOpsManager ops = getSystemService(android.app.AppOpsManager.class);
        boolean allowed = ops != null && ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
        if (!allowed) { watchingDouyin = false; studyUntil = 0; usageCursor = 0; foregroundPackage = ""; return; }
        long now = System.currentTimeMillis();
        android.app.usage.UsageStatsManager usage = getSystemService(android.app.usage.UsageStatsManager.class);
        android.app.usage.UsageEvents events = usage.queryEvents(usageCursor == 0 ? now - 86400000L : usageCursor, now);
        usageCursor = now;
        android.app.usage.UsageEvents.Event event = new android.app.usage.UsageEvents.Event();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED)
                foregroundPackage = event.getPackageName();
            else if (event.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
                && foregroundPackage.equals(event.getPackageName())) foregroundPackage = "";
        }
        boolean active = "com.ss.android.ugc.aweme".equals(foregroundPackage)
            || "com.ss.android.ugc.aweme.lite".equals(foregroundPackage);
        long elapsed = SystemClock.elapsedRealtime();
        if (active && !watchingDouyin && (lastStudyReminder == 0 || elapsed - lastStudyReminder > 600000)) {
            studyUntil = elapsed + 10000;
            lastStudyReminder = elapsed;
        }
        watchingDouyin = active;
        if (!active) studyUntil = 0;
    }
    private final Runnable checkMusic = new Runnable() {
        @Override public void run() {
            android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
            boolean awake = power != null && power.isInteractive();
            if (pill != null) pill.setMotionEnabled(awake && !dragging);
            if (awake) {
                try { checkStudy(); } catch (RuntimeException ignored) { watchingDouyin = false; studyUntil = 0; }
                android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
                boolean active = audio != null && audio.isMusicActive();
                musicPlaying = active;
                render();
            }
            handler.postDelayed(this, 2000);
        }
    };
    private final android.content.BroadcastReceiver wakeReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) { render(); }
    };
    private int tapCount;
    private int selectedPose;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener sizeListener =
        (prefs, key) -> {
            if ("catSize".equals(key) && pill != null) pill.setCatSize(prefs.getInt(key, 100));
        };
    private final Runnable finishTap = () -> {
        int count = tapCount;
        tapCount = 0;
        if (count == 2 && !message.isEmpty()) {
            handler.removeCallbacks(this.clearMessage);
            this.clearMessage.run();
            return;
        }
        if (count == 2) {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.ai.assistance.operit");
            if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            else android.widget.Toast.makeText(this, "找不到 Operit", android.widget.Toast.LENGTH_SHORT).show();
        } else if (count == 1 && !message.isEmpty()) {
            expanded = true;
            render();
        }
    };
    private void recordTap() {
        if (pill != null) pill.bounce();
        handler.removeCallbacks(finishTap);
        if (++tapCount == 3) {
            tapCount = 0;
            selectedPose = (selectedPose + 1) % 24;
            getSharedPreferences("island",0).edit().putInt("selectedPose", selectedPose).apply();
            render();
        } else handler.postDelayed(finishTap, ViewConfiguration.getDoubleTapTimeout());
    }
    private final Runnable clearMessage = () -> { busy=false; expanded=false; message=""; label=""; render(); };
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        registerReceiver(wakeReceiver, new android.content.IntentFilter(Intent.ACTION_SCREEN_ON));
        getSharedPreferences("island",0).registerOnSharedPreferenceChangeListener(sizeListener);
        selectedPose = Math.floorMod(getSharedPreferences("island",0).getInt("selectedPose",0), 24);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("island", "猫猫岛运行状态", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, IslandService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "island").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Leander 的猫猫岛已开启").setContentText("直接拖动 · 单击展开 · 双击收消息或打开 Operit · 三击换猫").setContentIntent(open).addAction(new Notification.Action.Builder(null, "关闭", stop).build()).setOngoing(true).build());
        windows = (WindowManager)getSystemService(WINDOW_SERVICE);
        running = true;
        handler.post(checkMusic);
        receiver = new Thread(this::receive, "island-sse"); receiver.start();
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        try {
            if (pill == null) {
                pill = new CatPill(this); pill.setTextColor(Color.WHITE); pill.setTextSize(11); pill.setGravity(Gravity.CENTER);
                pill.setCatSize(getSharedPreferences("island",0).getInt("catSize",100));
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(-2,-2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                if(android.os.Build.VERSION.SDK_INT>=30) p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; p.y = dp(getSharedPreferences("island",0).getInt("topOffset",40));
                android.content.SharedPreferences position=getSharedPreferences("island",0);
                if(position.getBoolean("dragged",false)) { p.gravity=Gravity.TOP|Gravity.LEFT; p.x=position.getInt("dragX",0); p.y=position.getInt("dragY",0); }
                overlayParams=p;
                updateWidth();
                pill.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
                    if (r-l != or-ol || b-t != ob-ot) keepOnScreen();
                });
                pill.setOnTouchListener((v,event)->{
                    if(event.getActionMasked()==MotionEvent.ACTION_DOWN) {
                        downX=event.getRawX(); downY=event.getRawY(); dragging=false;
                        handler.removeCallbacks(finishTap);
                        int[] location=new int[2]; pill.getLocationOnScreen(location); originX=location[0]; originY=location[1];
                        return true;
                    }
                    if(event.getActionMasked()==MotionEvent.ACTION_MOVE) {
                        float dx=event.getRawX()-downX,dy=event.getRawY()-downY;
                        if(Math.hypot(dx,dy)>ViewConfiguration.get(this).getScaledTouchSlop()) {
                            dragging=true; handler.removeCallbacks(finishTap); tapCount=0;
                            pill.setMotionEnabled(false);
                        }
                        if(dragging) {
                            overlayParams.gravity=Gravity.TOP|Gravity.LEFT;
                            android.util.DisplayMetrics screen=getResources().getDisplayMetrics();
                            overlayParams.x=Math.max(0,Math.min(screen.widthPixels-pill.getWidth(),originX+Math.round(dx)));
                            overlayParams.y=Math.max(0,Math.min(screen.heightPixels-pill.getHeight(),originY+Math.round(dy)));
                            windows.updateViewLayout(pill,overlayParams);
                        }
                        return true;
                    }
                    if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL) {
                        if(dragging) {
                            getSharedPreferences("island",0).edit().putBoolean("dragged",true).putInt("dragX",overlayParams.x).putInt("dragY",overlayParams.y).apply();
                            dragging=false;
                            pill.setMotionEnabled(true);
                        }
                        else if(event.getActionMasked()==MotionEvent.ACTION_UP) {
                            recordTap();
                        } else { tapCount=0; handler.removeCallbacks(finishTap);
                        }
                        return true;
                    }
                    return true;
                });
                windows.addView(pill, p);
            }
            render();
        } catch (RuntimeException e) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (pill != null) {
            updateWidth();
            pill.post(this::keepOnScreen);
        }
    }
    private void updateWidth() {
        pill.setMaxWidth(Math.min(dp(290), getResources().getDisplayMetrics().widthPixels - dp(16)));
    }
    private void keepOnScreen() {
        if (pill == null || !pill.isAttachedToWindow() || dragging) return;
        android.util.DisplayMetrics screen = getResources().getDisplayMetrics();
        android.content.SharedPreferences prefs = getSharedPreferences("island", 0);
        int x = prefs.getInt("dragX", 0);
        int y = prefs.getInt("dragY", 0);
        if (!prefs.getBoolean("dragged", false)) {
            x = 0;
            y = dp(prefs.getInt("topOffset", 40));
        }
        x = Math.max(0, Math.min(x, screen.widthPixels - pill.getWidth()));
        y = Math.max(0, Math.min(y, screen.heightPixels - pill.getHeight()));
        if (overlayParams.x != x || overlayParams.y != y) {
            overlayParams.x = x;
            overlayParams.y = y;
            windows.updateViewLayout(pill, overlayParams);
        }
    }
    private void showPush(String content, boolean status) {
        if (content.trim().isEmpty()) return;
        busy = status;
        handler.removeCallbacks(clearMessage);
        message = content.trim();
        messageDeadline = SystemClock.elapsedRealtime() + MESSAGE_DURATION_MS;
        String preview = message.replaceAll("\\s+", " ");
        int length = preview.codePointCount(0, preview.length());
        label = length > 8 ? preview.substring(0, preview.offsetByCodePoints(0, 8)) + "…" : preview;
        expanded = false;
        render();
        if (pill != null) pill.bounce();
        handler.postDelayed(clearMessage, MESSAGE_DURATION_MS);
    }
    private void render() {
        if (!message.isEmpty() && SystemClock.elapsedRealtime() >= messageDeadline) {
            busy=false; expanded=false; message=""; label="";
            handler.removeCallbacks(clearMessage);
        }
        if (pill == null) return;
        pill.setBusy(busy);
        boolean study = message.isEmpty() && watchingDouyin && SystemClock.elapsedRealtime() < studyUntil;
        pill.setIdle(!busy && message.isEmpty() && !study);
        pill.setPose(watchingDouyin ? 7 : musicPlaying ? 8 : selectedPose);
        pill.setListening(musicPlaying && !watchingDouyin);
        pill.setText(study ? "别刷太久，记得学习♡" : expanded ? message : label);
    }
    private void receive() {
        int retry = 1000;
        while (running) {
            try {
                android.content.SharedPreferences prefs = getSharedPreferences("island",0);
                String endpoint = prefs.getString("endpoint", "");
                if (endpoint.isEmpty()) { Thread.sleep(2000); continue; }
                URL url = new URL(endpoint + (endpoint.contains("?") ? "&" : "?") + "live=1");
                if (!"https".equals(url.getProtocol())) { Thread.sleep(5000); continue; }
                connection = (HttpURLConnection)url.openConnection(); connection.setConnectTimeout(15000); connection.setReadTimeout(75000);
                connection.setRequestProperty("Accept","text/event-stream");
                String token = prefs.getString("token", ""); if (!token.isEmpty()) connection.setRequestProperty("Authorization","Bearer " + token);
                String last = prefs.getString("lastId", ""); if (!last.isEmpty()) connection.setRequestProperty("Last-Event-ID",last);
                if (connection.getResponseCode() != 200) throw new IOException("Connection rejected");
                retry = 1000;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(),"UTF-8"))) {
                    String line, type = "message", eventId = ""; StringBuilder data = new StringBuilder();
                    while (running && (line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (data.length() > 0) {
                                JSONObject obj = new JSONObject(data.toString()); String kind = obj.optString("type", type).toLowerCase(java.util.Locale.ROOT);
                                String content = obj.optString("text",obj.optString("content",obj.optString("message","")));
                                if (!eventId.isEmpty() && eventId.equals(prefs.getString("lastId",""))) { data.setLength(0); type="message"; eventId=""; continue; }
                                if ("message".equals(kind)) handler.post(() -> showPush(content, false));
                                if (!eventId.isEmpty()) prefs.edit().putString("lastId",eventId).apply();
                            }
                            data.setLength(0); type="message"; eventId="";
                        } else if (line.startsWith("data:")) { if(data.length()>0) data.append('\n'); data.append(line.substring(5).trim()); }
                        else if (line.startsWith("event:")) type=line.substring(6).trim();
                        else if (line.startsWith("id:")) eventId=line.substring(3).trim();
                    }
                }
            } catch (Exception ignored) { }
            finally { if (connection != null) connection.disconnect(); }
            if (running) try { Thread.sleep(retry); retry=Math.min(30000,retry*2); } catch (InterruptedException e) { break; }
        }
    }
    @Override public void onDestroy() {
        unregisterReceiver(wakeReceiver);
        getSharedPreferences("island",0).unregisterOnSharedPreferenceChangeListener(sizeListener);
        running=false; if(connection!=null) connection.disconnect(); if(receiver!=null) receiver.interrupt(); handler.removeCallbacksAndMessages(null);
        if(pill!=null) windows.removeView(pill); super.onDestroy();
    }
}
