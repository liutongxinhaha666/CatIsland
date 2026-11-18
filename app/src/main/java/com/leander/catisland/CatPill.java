package com.leander.catisland;
import android.content.Context;
import android.graphics.*;
import android.widget.TextView;
public class CatPill extends TextView {
    private final Paint paint=new Paint(3);
    private boolean busy;
    private boolean idle;
    private final float density;
    private Bitmap character;
    private final Rect[] poses = new Rect[24];
    private static final int IDLE_WIDTH_DP = 162;
    private static final int IDLE_ART_HEIGHT_DP = 87;
    private int pose;
    private int catSize = 100;
    private boolean motionEnabled, listening;
    private long bounceStart = -10000;
    public void setMotionEnabled(boolean enabled) { motionEnabled = enabled; invalidate(); }
    public void setListening(boolean value) { listening = value; invalidate(); }
    public void bounce() { bounceStart = android.os.SystemClock.uptimeMillis(); invalidate(); }
    public CatPill(Context context) {
        super(context); density=getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE,null); setIncludeFontPadding(false);
        try(java.io.InputStream stream=context.getAssets().open("leander-poses.png")) {
            character=BitmapFactory.decodeStream(stream);
            int[] rows = {0, 285, 542, 775, 1024};
            int[][] columns = {
                {0, 280, 515, 783, 1020, 1260, 1536},
                {0, 275, 515, 770, 1020, 1270, 1536},
                {0, 255, 505, 735, 1005, 1260, 1536},
                {0, 275, 510, 755, 1000, 1260, 1536}
            };
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 6; col++) {
                    poses[row * 6 + col] = alphaBounds(columns[row][col], rows[row], columns[row][col + 1], rows[row + 1]);
                }
            }
        }
        catch(java.io.IOException e) { android.util.Log.e("CatIsland","Missing character asset",e); }
    }
    private int dp(float v) { return Math.round(v*density); }
    private Rect alphaBounds(int left, int top, int right, int bottom) {
        int minX = right, minY = bottom, maxX = left, maxY = top;
        int[] scanline = new int[right - left];
        for (int y = top; y < bottom; y++) {
            character.getPixels(scanline, 0, scanline.length, left, y, scanline.length, 1);
            for (int x = 0; x < scanline.length; x++) {
                if (Color.alpha(scanline[x]) <= 16) continue;
                minX = Math.min(minX, left + x);
                maxX = Math.max(maxX, left + x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        return minX <= maxX ? new Rect(minX, minY, maxX + 1, maxY + 1) : new Rect(left, top, right, bottom);
    }
    public void setPose(int value) { pose = value; invalidate(); }
    private void drawPose(Canvas canvas, float width, float height) {
        if (character == null) return;
        Rect source = poses[pose];
        float scale = Math.min((width - 4) / source.width(), height / source.height());
        float drawWidth = source.width() * scale, drawHeight = source.height() * scale;
        canvas.save();
        if (motionEnabled) {
            long now = android.os.SystemClock.uptimeMillis();
            float breath = (float)Math.sin(now * Math.PI * 2 / 3800);
            float age = (now - bounceStart) / 600f;
            float hop = age >= 0 && age < 1 ? (float)Math.sin(age * Math.PI) * 3 : 0;
            canvas.translate(0, -hop);
            canvas.scale(.97f, .97f + breath * .012f, width/2, height);
            if (listening) {
                double phase = now * Math.PI * 2 / 1800;
                // Leave space around the artwork so ears stay inside the overlay.
                canvas.scale(.86f, .86f, width/2, height/2);
                canvas.translate((float)Math.sin(phase) * height * .025f,
                    -(float)(1 - Math.cos(phase * 2)) * height * .018f);
                canvas.rotate((float)Math.sin(phase) * 7, width/2, height * .7f);
            }
            postInvalidateDelayed(50);
        }
        canvas.drawBitmap(character, source, new RectF((width-drawWidth)/2, height-drawHeight, (width+drawWidth)/2, height), paint);
        canvas.restore();
    }
    public void setBusy(boolean value) { busy=value; invalidate(); }
    public void setCatSize(int percent) {
        catSize = Math.max(40, Math.min(140, percent));
        setIdle(idle);
    }
    public void setIdle(boolean value) {
        idle = value;
        setPadding(dp(value ? 4 : 16), dp(value ? 0 : 30), dp(value ? 4 : 16), dp(value ? 0 : 10));
        setMinWidth(dp(value ? IDLE_WIDTH_DP * catSize / 100f : 82));
        setMinHeight(dp(value ? IDLE_ART_HEIGHT_DP * catSize / 100f + 3 : 56));
        invalidate();
    }
    @Override protected void onDraw(Canvas c) {
        c.save(); c.scale(density,density);
        float w=getWidth()/density,h=getHeight()/density;
        if (idle) {
            drawPose(c, w, IDLE_ART_HEIGHT_DP * catSize / 100f);
            c.restore();
            return;
        }
        paint.setColor(Color.rgb(23,17,24)); paint.setStyle(Paint.Style.FILL);
        paint.setShadowLayer(4,0,0,Color.rgb(255,151,203));
        c.drawRoundRect(5,30,w-5,h-4,18,18,paint); paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(Color.rgb(255,192,224));
        c.drawRoundRect(5,30,w-5,h-4,18,18,paint); paint.setStyle(Paint.Style.FILL);
        drawPose(c, w, 30);
        if(getText().toString().trim().isEmpty()) {
            paint.setColor(Color.rgb(250,163,204));
            c.drawOval(w/2-5,45,w/2+5,52,paint);
            for(int i=0;i<4;i++) {
                float x=w/2-8+i*5.3f, y=(i==0||i==3)?41:38;
                c.drawOval(x-2,y-2,x+2,y+2,paint);
            }
        }
        if(busy) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f); paint.setColor(Color.rgb(255,170,210));
            c.drawArc(w-19,41,w-11,49,(android.os.SystemClock.uptimeMillis()%1200)*.3f,260,false,paint);
            paint.setStyle(Paint.Style.FILL); postInvalidateDelayed(32);
        }
        c.restore(); super.onDraw(c);
    }
}
