package com.padpowerlab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.util.ArrayList;
import java.util.Locale;

final class PowerChart extends View {
    private final ArrayList<Double> points = new ArrayList<>();
    private final Paint paint = new Paint(3);
    private final Path path = new Path();
    PowerChart(Context c) { super(c); setContentDescription("最近 180 秒电池净功率曲线，正值充入，负值放出"); }
    void add(double value) { if (points.size() == 180) points.remove(0); points.add(value); invalidate(); }
    void clear() { points.clear(); invalidate(); }
    @Override protected void onDraw(Canvas c) {
        float d=getResources().getDisplayMetrics().density;
        float left=48*d, right=getWidth()-12*d, top=10*d, bottom=getHeight()-24*d;
        double max=5;
        for (double p:points) if (Double.isFinite(p)) max=Math.max(max,Math.abs(p)*1.15);
        max=Math.ceil(max/5)*5;
        paint.setTypeface(android.graphics.Typeface.MONOSPACE); paint.setTextSize(10*d); paint.setStrokeWidth(d);
        for (int i=0;i<5;i++) {
            float y=top+(bottom-top)*i/4;
            paint.setColor(i==2 ? Color.rgb(74,91,110) : Color.rgb(38,52,69)); c.drawLine(left,y,right,y,paint);
            paint.setColor(Color.rgb(133,152,169)); c.drawText(String.format(Locale.US,"%+.0f",max*(1-i/2.0)),3*d,y+4*d,paint);
        }
        paint.setColor(Color.rgb(133,152,169));
        c.drawText("−180 s",left,getHeight()-4*d,paint); c.drawText("现在",right-25*d,getHeight()-4*d,paint);
        path.reset(); boolean connected=false;
        for (int i=0;i<points.size();i++) {
            double p=points.get(i);
            if (!Double.isFinite(p)) { connected=false; continue; }
            float x=right-(points.size()-1-i)*(right-left)/179f;
            float y=(float)(top+(bottom-top)*(.5-p/(2*max)));
            if (!connected) path.moveTo(x,y); else path.lineTo(x,y);
            connected=true;
        }
        paint.setColor(Color.rgb(196,245,107)); paint.setStrokeWidth(2*d); paint.setStyle(Paint.Style.STROKE); c.drawPath(path,paint); paint.setStyle(Paint.Style.FILL);
    }
}
