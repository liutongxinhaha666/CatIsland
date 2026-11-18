package com.leander.catisland;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        if("treehole".equals(getIntent().getAction())) showTreehole(); else showHome();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if("treehole".equals(intent.getAction())) showTreehole(); }
    private void showHome() {
        FrameLayout layout = new FrameLayout(this) {
            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                float scale = Math.min((r-l)/576f, (b-t)/1280f);
                int x = Math.round(((r-l)-576*scale)/2);
                int y = Math.round(((b-t)-1280*scale)/2);
                getChildAt(0).layout(x,y,x+Math.round(576*scale),y+Math.round(1280*scale));
                int[] tops = {372,472,574,675,777};
                for (int i=0;i<tops.length;i++) {
                    getChildAt(i+1).layout(x+Math.round(76*scale), y+Math.round(tops[i]*scale),
                        x+Math.round(508*scale), y+Math.round((tops[i]+82)*scale));
                }
                getChildAt(6).layout(x+Math.round(187*scale), y+Math.round(690*scale),
                    x+Math.round(447*scale), y+Math.round(747*scale));
            }
        };
        layout.setBackgroundColor(0xffffeae8);
        getWindow().setStatusBarColor(0xffffeae8);
        getWindow().setNavigationBarColor(0xffffeae8);
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.FIT_XY);
        try (java.io.InputStream image = getAssets().open("home-pink.jpg")) {
            art.setImageBitmap(BitmapFactory.decodeStream(image));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Missing home artwork", e);
        }
        layout.addView(art);
        button(layout, "开启猫猫岛", () -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, "请允许悬浮窗，返回后再次点击开启", Toast.LENGTH_LONG).show();
            } else showIsland();
        });
        button(layout, "打开星星树洞", this::showTreehole);
        button(layout, "推送连接设置", this::connectionSettings);
        button(layout, "调整猫猫岛大小", this::sizeSettings);
        button(layout, "关闭猫猫岛", this::removeIsland);
        TextView sizeLabel = new TextView(this);
        sizeLabel.setText("调整猫猫岛大小");
        sizeLabel.setTextColor(0xff674446);
        sizeLabel.setBackgroundColor(0xfffae6e3);
        sizeLabel.setGravity(android.view.Gravity.CENTER);
        sizeLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        sizeLabel.setAutoSizeTextTypeUniformWithConfiguration(4, 23, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        sizeLabel.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        layout.addView(sizeLabel);
        setContentView(layout);
    }
    private void button(FrameLayout layout, String text, Runnable action) {
        Button b = new Button(this);
        b.setContentDescription(text);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x44d2769e), null, new android.graphics.drawable.ColorDrawable(Color.WHITE)));
        b.setStateListAnimator(null);
        b.setOnClickListener(v -> action.run());
        layout.addView(b);
    }
    private void showIsland() {
        startForegroundService(new Intent(this, IslandService.class));
    }
    private void removeIsland() { stopService(new Intent(this, IslandService.class)); }
    private void sizeSettings() {
        android.content.SharedPreferences prefs=getSharedPreferences("island",0);
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(32,16,32,16);
        TextView value=new TextView(this); box.addView(value);
        Button study = new Button(this);
        study.setText("开启 / 关闭抖音学习提醒");
        study.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
            Uri.parse("package:" + getPackageName()))));
        box.addView(study);
        TextView explanation = new TextView(this);
        explanation.setText("允许使用情况访问后，刷抖音会换成抱书猫并短暂提醒学习。应用使用记录仅在手机本地判断，不上传。");
        box.addView(explanation);
        CatPill preview = new CatPill(this);
        preview.setPose(Math.floorMod(prefs.getInt("selectedPose",0),24));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-2,-2);
        previewParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        box.addView(preview, previewParams);
        android.widget.SeekBar seek=new android.widget.SeekBar(this); seek.setMax(100); seek.setProgress(prefs.getInt("catSize",100)-40); box.addView(seek);
        preview.setCatSize(seek.getProgress()+40);
        preview.setIdle(true);
        value.setText("图案大小："+(seek.getProgress()+40)+"%");
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar s,int p,boolean u){value.setText("图案大小："+(p+40)+"%"); preview.setCatSize(p+40);}
            public void onStartTrackingTouch(android.widget.SeekBar s){}
            public void onStopTrackingTouch(android.widget.SeekBar s){}
        });
        new android.app.AlertDialog.Builder(this).setTitle("调整猫猫岛大小").setView(box).setNegativeButton("取消",null).setPositiveButton("应用",(d,w)->{
            prefs.edit().putInt("catSize",seek.getProgress()+40).apply();
        }).show();
    }
    private void connectionSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences("island",0);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(32,24,32,16);
        android.widget.EditText endpoint = new android.widget.EditText(this); endpoint.setHint("SSE 地址（推荐 HTTPS）"); endpoint.setText(prefs.getString("endpoint","")); endpoint.setSingleLine(); box.addView(endpoint);
        android.widget.EditText token = new android.widget.EditText(this); token.setHint("手机专用令牌"); token.setInputType(129); token.setText(prefs.getString("token","")); box.addView(token);
        android.widget.EditText treehole = new android.widget.EditText(this);
        treehole.setHint("树洞 HTTPS 地址（可选）");
        treehole.setSingleLine();
        treehole.setText(prefs.getString("treehole", ""));
        box.addView(treehole);
        new android.app.AlertDialog.Builder(this).setTitle("连接 Leander").setView(box).setMessage("保存后关闭并重新开启猫猫岛。").setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
            String url=endpoint.getText().toString().trim();
            if(!url.isEmpty()) try { java.net.URL u=new java.net.URL(url); if(!"https".equals(u.getProtocol())) throw new Exception(); }
            catch(Exception e) { Toast.makeText(this,"请输入 HTTPS 地址",Toast.LENGTH_LONG).show(); return; }
            String treeholeUrl = treehole.getText().toString().trim();
            Uri treeholeUri = Uri.parse(treeholeUrl);
            if (!treeholeUrl.isEmpty() && (!"https".equals(treeholeUri.getScheme()) || treeholeUri.getHost() == null)) {
                Toast.makeText(this, "请输入树洞的 HTTPS 地址", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString("treehole", treeholeUrl).putString("endpoint",url).putString("token",token.getText().toString().trim()).remove("lastId").apply();
        }).show();
    }
    private void showTreehole() {
        String url = getSharedPreferences("island", 0).getString("treehole", "");
        if (url.isEmpty()) {
            connectionSettings();
            return;
        }
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xffffeae8);
        Button back = new Button(this);
        back.setText("‹  返回猫猫岛");
        back.setTextColor(0xff674446);
        back.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffffd8e5));
        page.addView(back, new LinearLayout.LayoutParams(-1, -2));
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        page.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        back.setOnClickListener(v -> showHome());
        setContentView(page);
        web.loadUrl(url);
    }
    @Override public void onBackPressed() { showHome(); }
    @Override protected void onDestroy() { super.onDestroy(); }
}
