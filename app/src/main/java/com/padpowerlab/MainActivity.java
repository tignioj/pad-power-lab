package com.padpowerlab;

import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BG=0xff0b111b, PANEL=0xff141f2d, BORDER=0xff28384a;
    private static final int INK=0xffedf3f7, MUTED=0xff91a3b6, LIME=0xffc4f56b, AMBER=0xffffcb7d;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CpuLoad cpu = new CpuLoad();
    private final Telemetry data = new Telemetry();
    private GpuLoadView gpu;
    private PowerChart chart;
    private TextView badge, elapsedText, notice, wattsText, powerDetail, tempText, batteryText, fpsText, thermalText, gpuInfo, sessionText;
    private TextView threadsLabel, dutyLabel, gpuLabel, brightnessLabel;
    private SeekBar threadsBar, dutyBar, gpuBar, brightnessBar;
    private Spinner resolutionSpinner, frameSpinner, durationSpinner, cutoffSpinner;
    private Button startButton, exportButton;
    private boolean running, configuring, resumed;
    private int cpuThreads=4, cpuDuty=70, gpuSteps=48, brightness=80;
    private int resolutionIndex=1, frameIndex=0, durationIndex=1, cutoffIndex=0;
    private final int maxThreads=Math.min(16,Runtime.getRuntime().availableProcessors());
    private final int[] durations={5,10,20,30,0};
    private long startTime, lastElapsed;
    private double sumWatts, peakAbsWatts;
    private int validSamples, rows;
    private BufferedWriter writer;
    private File sessionFile;
    private String stopReason="准备就绪 · 点击开始施加负载", logError;
    private PowerManager power;
    private final PowerManager.OnThermalStatusChangedListener thermalListener = status -> {
        data.thermal = status;
        if (running && status >= PowerManager.THERMAL_STATUS_SEVERE) stopTest("系统报告严重限频，已自动停止");
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            data.read(MainActivity.this);
            long now=SystemClock.elapsedRealtime();
            if (running) {
                lastElapsed=now-startTime;
                writeSample();
                if (Double.isFinite(data.watts)) { sumWatts+=data.watts; validSamples++; peakAbsWatts=Math.max(peakAbsWatts,Math.abs(data.watts)); }
                String blocked=blockedReason();
                if (blocked!=null) stopTest(blocked);
                else if (gpu.error!=null && gpuSteps>0) stopTest(gpu.error);
                else if (durations[durationIndex]>0 && lastElapsed>=durations[durationIndex]*60_000L) stopTest("设定时长已到，测试完成");
            }
            chart.add(data.watts);
            updateReadings();
            handler.postDelayed(this,1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        power=(PowerManager)getSystemService(POWER_SERVICE);
        loadSettings();
        buildUi();
        power.addThermalStatusListener(getMainExecutor(),thermalListener);
    }
    private int dp(float v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private GradientDrawable box(int fill,int stroke,int radius) {
        GradientDrawable b=new GradientDrawable(); b.setColor(fill); b.setCornerRadius(dp(radius));
        if(stroke!=0)b.setStroke(dp(1),stroke); return b;
    }
    private TextView text(String s,float sp,int color) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setIncludeFontPadding(false); return t;
    }
    private void mono(TextView t) { t.setTypeface(Typeface.create("monospace",Typeface.NORMAL)); }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout card() { LinearLayout l=column(); l.setPadding(dp(18),dp(16),dp(18),dp(16)); l.setBackground(box(PANEL,BORDER,18)); return l; }
    private void gap(LinearLayout l,int h) { l.addView(new View(this),new LinearLayout.LayoutParams(1,dp(h))); }
    private void addCard(LinearLayout parent,View child) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(12); parent.addView(child,p);
    }
    private Button button(String label,boolean primary) {
        Button b=new Button(this); b.setText(label); b.setTextSize(14); b.setAllCaps(false);
        b.setTextColor(primary?BG:INK); b.setTypeface(null,Typeface.BOLD); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setBackground(box(primary?LIME:0xff213145,primary?0:BORDER,12));
        b.setPadding(dp(12),dp(10),dp(12),dp(10)); return b;
    }
    private ScrollView scroll(View content) { ScrollView s=new ScrollView(this); s.setFillViewport(false); s.setClipToPadding(false); s.setVerticalScrollBarEnabled(false); s.addView(content); return s; }

    private void buildUi() {
        LinearLayout root=column(); root.setBackgroundColor(BG); root.setPadding(dp(24),dp(16),dp(24),dp(12));
        LinearLayout header=row();
        LinearLayout brand=column();
        TextView eyebrow=text("POWER / LAB     01",10,LIME); eyebrow.setLetterSpacing(.18f); mono(eyebrow); brand.addView(eyebrow);
        gap(brand,7); TextView title=text("平板功耗实验室",25,INK); title.setTypeface(null,Typeface.BOLD); brand.addView(title);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        badge=text("●  待机",13,LIME); badge.setPadding(dp(15),dp(10),dp(15),dp(10)); badge.setBackground(box(0xff202e25,0,24)); header.addView(badge);
        root.addView(header); gap(root,18);
        LinearLayout body=row(); body.setGravity(Gravity.TOP);
        LinearLayout controls=card(); controls.addView(text("01   负载控制",16,INK)); gap(controls,5);
        controls.addView(text("调高负载，观察外接功率表的变化",11,MUTED)); gap(controls,14);
        LinearLayout presets=row();
        String[] names={"轻载","游戏负载","极限负载"};
        for(int i=0;i<3;i++) {
            final int p=i; Button b=button(names[i],false); b.setTextSize(12); b.setPadding(dp(4),dp(9),dp(4),dp(9));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(40),1); if(i>0)lp.leftMargin=dp(6); presets.addView(b,lp);
            b.setOnClickListener(v->applyPreset(p));
        }
        controls.addView(presets); gap(controls,13);
        threadsLabel=text("",12,INK); threadsBar=slider(controls,threadsLabel,maxThreads,cpuThreads,value->{cpuThreads=value; cpuChanged();});
        dutyLabel=text("",12,INK); dutyBar=slider(controls,dutyLabel,100,cpuDuty,value->{cpuDuty=value; cpuChanged();});
        gpuLabel=text("",12,INK); gpuBar=slider(controls,gpuLabel,160,gpuSteps,value->{gpuSteps=value; gpuChanged();});
        controls.addView(text("GPU 渲染分辨率",11,MUTED));
        resolutionSpinner=spinner(controls,new String[]{"1280 × 854 · 轻","1920 × 1280 · 中","2560 × 1708 · 高","屏幕原生像素 · 极高"},resolutionIndex,i->{resolutionIndex=i; gpuChanged();});
        controls.addView(text("目标帧率",11,MUTED));
        frameSpinner=spinner(controls,new String[]{"60 FPS","120 FPS","屏幕最高刷新率"},frameIndex,i->{frameIndex=i; gpuChanged();});
        brightnessLabel=text("",12,INK); brightnessBar=slider(controls,brightnessLabel,100,brightness,value->{brightness=Math.max(5,value); updateLabels(); if(running)applyWindowLoad();});
        LinearLayout options=row();
        LinearLayout timer=column(); timer.addView(text("自动停止",11,MUTED));
        durationSpinner=spinner(timer,new String[]{"5 分钟","10 分钟","20 分钟","30 分钟","手动停止"},durationIndex,i->durationIndex=i);
        LinearLayout cutoff=column(); cutoff.addView(text("电池温度上限",11,MUTED));
        cutoffSpinner=spinner(cutoff,new String[]{"43 °C","45 °C"},cutoffIndex,i->{cutoffIndex=i; if(running){ data.read(this); String why=blockedReason(); if(why!=null)stopTest(why); }});
        options.addView(timer,new LinearLayout.LayoutParams(0,-2,1)); options.addView(cutoff,new LinearLayout.LayoutParams(0,-2,1)); controls.addView(options);
        gap(controls,8); startButton=button("开始负载测试",true); controls.addView(startButton,new LinearLayout.LayoutParams(-1,dp(49)));
        startButton.setOnClickListener(v->{if(running)stopTest("已手动停止");else startTest();});
        gap(controls,9); exportButton=button("导出本次 CSV",false); exportButton.setEnabled(false); exportButton.setAlpha(.45f);
        controls.addView(exportButton,new LinearLayout.LayoutParams(-1,dp(43))); exportButton.setOnClickListener(v->exportCsv());
        gap(controls,10); controls.addView(text("退出应用或锁屏即停止；过热自动停止。\n亮度与刷新率仅在测试期间设置。",10,MUTED));
        body.addView(scroll(controls),new LinearLayout.LayoutParams(dp(322),-1));
        View gutter=new View(this); body.addView(gutter,new LinearLayout.LayoutParams(dp(16),1));
        LinearLayout readings=column();
        LinearLayout powerCard=card();
        LinearLayout powerHead=row(); powerHead.addView(text("02   实时遥测",14,INK),new LinearLayout.LayoutParams(0,-2,1));
        elapsedText=text("00:00",13,MUTED); mono(elapsedText); powerHead.addView(elapsedText); powerCard.addView(powerHead); gap(powerCard,10);
        LinearLayout hero=row();
        LinearLayout watts=column(); TextView powerLabel=text("电池净功率 / W   ⓘ 电流方向",12,MUTED); watts.addView(powerLabel); gap(watts,4);
        powerLabel.setOnClickListener(v->showCurrentDirection());
        wattsText=text("—",46,LIME); mono(wattsText); watts.addView(wattsText);
        hero.addView(watts,new LinearLayout.LayoutParams(0,-2,1));
        powerDetail=text("正在读取电池…",12,MUTED); powerDetail.setLineSpacing(dp(4),1); hero.addView(powerDetail); powerCard.addView(hero);
        gap(powerCard,7);
        LinearLayout metrics=row();
        batteryText=metric(metrics,"电量"); tempText=metric(metrics,"电池温度"); fpsText=metric(metrics,"渲染帧率"); thermalText=metric(metrics,"温控状态"); powerCard.addView(metrics);
        addCard(readings,powerCard);
        LinearLayout renderCard=card();
        LinearLayout renderHead=row(); renderHead.addView(text("03   GPU 负载画面",13,INK),new LinearLayout.LayoutParams(0,-2,1));
        gpuInfo=text("OpenGL ES 3.0",10,MUTED); renderHead.addView(gpuInfo); renderCard.addView(renderHead); gap(renderCard,10);
        gpu=new GpuLoadView(this); renderCard.addView(gpu,new LinearLayout.LayoutParams(-1,dp(142)));
        addCard(readings,renderCard);
        LinearLayout chartCard=card();
        LinearLayout chartHead=row(); chartHead.addView(text("04   电池净功率趋势",13,INK),new LinearLayout.LayoutParams(0,-2,1));
        chartHead.addView(text("＋充入   /   −放出",10,LIME)); chartCard.addView(chartHead); gap(chartCard,8);
        chart=new PowerChart(this); chartCard.addView(chart,new LinearLayout.LayoutParams(-1,dp(82)));
        sessionText=text("每秒采样 · 测试时自动记录",10,MUTED); gap(chartCard,7); chartCard.addView(sessionText); addCard(readings,chartCard);
        notice=text(stopReason,12,LIME); readings.addView(notice); gap(readings,7);
        TextView note=text("电池侧估算功率，不能代表充电器输入或整机耗电；充电输入请看 USB 功率表。部分固件电流符号相反，可点「电流方向」校准。电量接近 100% 时充电功率会降低。",10,MUTED);
        note.setLineSpacing(dp(3),1); readings.addView(note);
        body.addView(scroll(readings),new LinearLayout.LayoutParams(0,-1,1));
        root.addView(body,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
        updateLabels(); gpuChanged();
    }

    private TextView metric(LinearLayout parent,String label) {
        LinearLayout block=column(); block.addView(text(label,10,MUTED)); gap(block,5);
        TextView value=text("—",17,INK); block.addView(value); parent.addView(block,new LinearLayout.LayoutParams(0,-2,1)); return value;
    }
    private interface Change { void set(int value); }
    private SeekBar slider(LinearLayout parent,TextView label,int max,int initial,Change change) {
        parent.addView(label); SeekBar bar=new SeekBar(this); bar.setMax(max); bar.setProgress(initial);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(34)); p.bottomMargin=dp(4); parent.addView(bar,p);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s,int v,boolean user) { if(user)change.set(v); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        }); return bar;
    }
    private Spinner spinner(LinearLayout parent,String[] labels,int initial,Change change) {
        Spinner s=new Spinner(this);
        ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels) {
            @Override public View getView(int position,View reuse,ViewGroup group) {
                TextView t=(TextView)super.getView(position,reuse,group); t.setTextColor(INK); t.setTextSize(12); t.setPadding(dp(4),0,0,0); return t;
            }
            @Override public View getDropDownView(int position,View reuse,ViewGroup group) {
                TextView t=(TextView)super.getDropDownView(position,reuse,group); t.setTextColor(INK); t.setBackgroundColor(PANEL); t.setTextSize(14); t.setPadding(dp(16),dp(15),dp(16),dp(15)); return t;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); s.setSelection(initial);
        parent.addView(s,new LinearLayout.LayoutParams(-1,dp(39)));
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> p,View v,int position,long id){change.set(position);}
            public void onNothingSelected(AdapterView<?> p){}
        }); return s;
    }
    private void updateLabels() {
        if(threadsLabel==null)return;
        threadsLabel.setText("CPU 线程 · "+cpuThreads+" / "+maxThreads);
        dutyLabel.setText("CPU 单线程目标占用 · "+cpuDuty+"%");
        gpuLabel.setText("GPU 着色迭代 · "+gpuSteps);
        brightnessLabel.setText("屏幕亮度 · "+brightness+"%");
    }
    private void showCurrentDirection() {
        if(running){Toast.makeText(this,"请先停止测试，再校准电流方向",Toast.LENGTH_SHORT).show();return;}
        new android.app.AlertDialog.Builder(this).setTitle("电流方向（拔线待机时净功率应为负值）")
            .setSingleChoiceItems(new String[]{"Android 标准：原始正值表示充入", "反转符号：原始负值表示充入"},data.currentSign==1?0:1,(dialog,which)->{
                data.currentSign=which==0?1:-1;chart.clear();saveSettings();data.read(this);updateReadings();dialog.dismiss();
            })
            .setNegativeButton("关闭",null).show();
    }
    private void cpuChanged() { updateLabels(); if(running&&!configuring)cpu.start(cpuThreads,cpuDuty); }
    private void gpuChanged() {
        updateLabels(); if(gpu==null)return;
        int w=1920,h=1280;
        if(resolutionIndex==0){w=1280;h=854;} else if(resolutionIndex==2){w=2560;h=1708;}
        else if(resolutionIndex==3){ android.graphics.Point size=new android.graphics.Point(); getWindowManager().getDefaultDisplay().getRealSize(size); w=Math.max(size.x,size.y);h=Math.min(size.x,size.y); }
        gpu.targetWidth=w; gpu.targetHeight=h; gpu.iterations=gpuSteps;
        float max=60;
        for(android.view.Display.Mode m:getWindowManager().getDefaultDisplay().getSupportedModes())max=Math.max(max,m.getRefreshRate());
        gpu.frameLimit=frameIndex==0?60:frameIndex==1?120:Math.round(max);
        if(!configuring){gpu.setActive(running&&gpuSteps>0);if(running)applyWindowLoad();}
    }
    private void applyPreset(int preset) {
        configuring=true;
        cpuThreads=preset==0?2:preset==1?Math.min(4,maxThreads):maxThreads;
        cpuDuty=preset==0?35:preset==1?70:100; gpuSteps=preset==0?16:preset==1?48:128;
        resolutionIndex=preset==0?0:preset==1?1:3; frameIndex=preset==2?2:0; brightness=preset==0?50:preset==1?80:100;
        threadsBar.setProgress(cpuThreads); dutyBar.setProgress(cpuDuty); gpuBar.setProgress(gpuSteps); brightnessBar.setProgress(brightness);
        resolutionSpinner.setSelection(resolutionIndex); frameSpinner.setSelection(frameIndex);
        configuring=false; cpuChanged(); gpuChanged();
    }
    private String blockedReason() {
        float cutoff=cutoffIndex==0?43:45;
        if(Float.isFinite(data.temperature)&&data.temperature>=cutoff)return "电池温度达到 "+(int)cutoff+" °C，已停止负载";
        if(data.thermal>=PowerManager.THERMAL_STATUS_SEVERE)return "系统报告严重限频，已停止负载";
        if(data.level>=0&&data.level<=10&&data.plugged==0)return "电量不高于 10% 且未接电源，已停止负载";
        return null;
    }
    private void startTest() {
        data.read(this); String why=blockedReason();
        if(why!=null){stopReason=why;notice.setText(why);notice.setTextColor(AMBER);return;}
        if(gpuSteps==0&&(cpuThreads==0||cpuDuty==0)){Toast.makeText(this,"请至少开启 CPU 或 GPU 负载",Toast.LENGTH_SHORT).show();return;}
        if(gpuSteps>0&&gpu.error!=null){notice.setText(gpu.error);notice.setTextColor(AMBER);return;}
        closeLog(); logError=null;
        try {
            File folder=new File(getFilesDir(),"sessions"); if(!folder.exists()&&!folder.mkdirs())throw new java.io.IOException("无法创建记录目录");
            sessionFile=new File(folder,"PowerLab_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date())+".csv");
            writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(sessionFile),StandardCharsets.UTF_8));
            writer.write("timestamp_unix_ms,elapsed_s,event,cpu_threads,cpu_duty_percent,gpu_iterations,gpu_width,gpu_height,target_fps,render_fps,brightness_percent,battery_percent,battery_temp_c,battery_mv,battery_raw_ua,battery_net_w,current_sign_multiplier,plugged,charge_status,thermal_status\n");writer.flush();
        }catch(Exception e){sessionFile=null;logError="记录创建失败："+e.getMessage();}
        startTime=SystemClock.elapsedRealtime();lastElapsed=0;sumWatts=0;peakAbsWatts=0;validSamples=0;rows=0;chart.clear();
        running=true;stopReason="测试进行中 · 每秒记录一次";cpu.start(cpuThreads,cpuDuty);gpuChanged();applyWindowLoad();
        startButton.setText("■  停止负载测试");startButton.setBackground(box(0xffffc17a,0,12));
        exportButton.setEnabled(false);exportButton.setAlpha(.45f); updateReadings();
    }
    private void stopTest(String reason) {
        if(!running)return;
        lastElapsed=SystemClock.elapsedRealtime()-startTime;running=false;cpu.stop();gpu.setActive(false);
        writeRow("stop:"+reason);closeLog();stopReason=reason;restoreWindow();saveSettings();
        startButton.setText("开始负载测试");startButton.setBackground(box(LIME,0,12));
        boolean exportable=sessionFile!=null&&sessionFile.exists();exportButton.setEnabled(exportable);exportButton.setAlpha(exportable?1:.45f);updateReadings();
    }
    private void applyWindowLoad() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams attrs=getWindow().getAttributes(); attrs.screenBrightness=brightness/100f;
        attrs.preferredRefreshRate=gpu.frameLimit;getWindow().setAttributes(attrs);
    }
    private void restoreWindow() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams a=getWindow().getAttributes();a.screenBrightness=WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;a.preferredRefreshRate=0;getWindow().setAttributes(a);
    }
    private String duration(long millis) { long s=millis/1000;return String.format(Locale.US,"%02d:%02d",s/60,s%60); }
    private void updateReadings() {
        if(wattsText==null)return;
        wattsText.setText(Double.isFinite(data.watts)?String.format(Locale.US,"%+.2f",data.watts):"—");
        String current=data.currentSupported?String.format(Locale.US,"%+.0f mA",data.microamps/1000f):"电流不支持";
        powerDetail.setText(data.charging()+"\n"+String.format(Locale.US,"%.3f V  ·  原始 %s",data.millivolts/1000f,current)+"\n＋充入 / −供电 · "+(data.currentSign==1?"标准符号":"已反转符号"));
        batteryText.setText(data.level>=0?data.level+"%":"—");
        tempText.setText(Float.isFinite(data.temperature)?String.format(Locale.US,"%.1f °C",data.temperature):"不可用");
        tempText.setTextColor(data.temperature>=40?AMBER:INK);
        fpsText.setText(running&&gpuSteps>0?String.format(Locale.US,"%.1f FPS",gpu.fps):"—");
        thermalText.setText(Telemetry.thermalName(data.thermal));thermalText.setTextColor(data.thermal>=2?AMBER:INK);
        badge.setText(running?"●  正在施加负载":"●  已停止 / 待机");elapsedText.setText(duration(lastElapsed));
        gpuInfo.setText(gpu.rendererName+(running&&gpuSteps>0?" · "+gpu.targetWidth+" × "+gpu.targetHeight:" · 待机"));
        String summary=validSamples>0?String.format(Locale.US,"%d 个样本   ·   平均净功率 %+.2f W   ·   峰值绝对值 %.2f W",rows,sumWatts/validSamples,peakAbsWatts):"每秒采样 · 测试时自动记录";
        sessionText.setText(summary);
        notice.setText(logError!=null?logError:stopReason);notice.setTextColor(logError!=null||(!running&&!stopReason.startsWith("准备"))?AMBER:LIME);
    }
    private void writeSample() { writeRow("sample");rows++; }
    private void writeRow(String event) {
        if(writer==null)return;
        try {
            String amps=data.currentSupported?Integer.toString(data.microamps):"";
            String watts=Double.isFinite(data.watts)?String.format(Locale.US,"%.6f",data.watts):"";
            String temp=Float.isFinite(data.temperature)?String.format(Locale.US,"%.1f",data.temperature):"";
            writer.write(String.format(Locale.US,"%d,%.3f,%s,%d,%d,%d,%d,%d,%d,%.2f,%d,%d,%s,%d,%s,%s,%d,%d,%d,%d\n",
                System.currentTimeMillis(),lastElapsed/1000.0,event,cpuThreads,cpuDuty,gpuSteps,gpu.targetWidth,gpu.targetHeight,gpu.frameLimit,gpu.fps,brightness,data.level,temp,data.millivolts,amps,watts,data.currentSign,data.plugged,data.status,data.thermal));writer.flush();
        } catch(Exception e){logError="数据记录失败："+e.getMessage();closeLog();}
    }
    private void closeLog() { if(writer!=null){try{writer.close();}catch(Exception ignored){}writer=null;} }
    private void exportCsv() {
        if(running||sessionFile==null)return;
        final File file=sessionFile;exportButton.setEnabled(false);
        new Thread(()->{
            Uri uri=null;
            try {
                ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,file.getName());values.put(MediaStore.MediaColumns.MIME_TYPE,"text/csv");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/PowerLab");values.put(MediaStore.MediaColumns.IS_PENDING,1);
                uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new java.io.IOException("无法创建下载文件");
                try(FileInputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(uri)) {
                    if(out==null)throw new java.io.IOException("无法打开下载文件");byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                }
                ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(uri,done,null,null);
                runOnUiThread(()->{stopReason="已导出：下载 / PowerLab / "+file.getName();notice.setText(stopReason);notice.setTextColor(LIME);exportButton.setEnabled(true);Toast.makeText(this,"CSV 已保存到 下载/PowerLab",Toast.LENGTH_LONG).show();});
            }catch(Exception e){if(uri!=null)getContentResolver().delete(uri,null,null);runOnUiThread(()->{Toast.makeText(this,"导出失败："+e.getMessage(),Toast.LENGTH_LONG).show();exportButton.setEnabled(true);});}
        },"PowerLab-Export").start();
    }
    private void loadSettings() {
        android.content.SharedPreferences p=getPreferences(MODE_PRIVATE);
        cpuThreads=Math.min(maxThreads,p.getInt("threads",Math.min(4,maxThreads)));cpuDuty=p.getInt("duty",70);gpuSteps=p.getInt("gpu",48);brightness=p.getInt("brightness",80);
        resolutionIndex=p.getInt("resolution",1);frameIndex=p.getInt("frame",0);durationIndex=p.getInt("duration",1);cutoffIndex=p.getInt("cutoff",0);
        data.currentSign=p.getInt("currentSign",1);
        String last=p.getString("lastFile",null);if(last!=null){File f=new File(getFilesDir(),"sessions/"+last);if(f.isFile())sessionFile=f;}
    }
    private void saveSettings() {
        android.content.SharedPreferences.Editor e=getPreferences(MODE_PRIVATE).edit().putInt("threads",cpuThreads).putInt("duty",cpuDuty).putInt("gpu",gpuSteps).putInt("brightness",brightness)
            .putInt("resolution",resolutionIndex).putInt("frame",frameIndex).putInt("duration",durationIndex).putInt("cutoff",cutoffIndex).putInt("currentSign",data.currentSign);
        if(sessionFile!=null)e.putString("lastFile",sessionFile.getName());e.apply();
    }
    @Override protected void onResume() {
        super.onResume();resumed=true;gpu.onResume();
        boolean available=sessionFile!=null&&sessionFile.exists();exportButton.setEnabled(available);exportButton.setAlpha(available?1:.45f);
        handler.removeCallbacks(tick);handler.post(tick);
    }
    @Override protected void onPause() {
        resumed=false;handler.removeCallbacks(tick);if(running)stopTest("应用离开前台，已自动停止");cpu.stop();gpu.setActive(false);gpu.onPause();restoreWindow();saveSettings();super.onPause();
    }
    @Override protected void onDestroy() {handler.removeCallbacksAndMessages(null);cpu.stop();closeLog();power.removeThermalStatusListener(thermalListener);super.onDestroy();}
}
