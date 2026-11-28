package com.novelmemory.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps user-initiated model calls alive while the UI is in the background. */
public final class BackgroundWorkService extends Service {
    static final String CHANNEL="pardo_work_status";
    static final int ID=101;
    static final String UPDATE="com.novelmemory.mobile.UPDATE_WORK";
    static final String STOP="com.novelmemory.mobile.STOP_WORK";
    @Override public void onCreate(){super.onCreate();((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
        .createNotificationChannel(new NotificationChannel(CHANNEL,"当前工作状态",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){
            if(!MainActivity.hasActiveWork()){
                stopForeground(MainActivity.hasVisibleUi()?STOP_FOREGROUND_DETACH:STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        String detail=intent==null?"正在处理任务":intent.getStringExtra("detail");
        if(detail==null||detail.isEmpty())detail="正在处理任务";
        Intent open=new Intent(this,MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open_work_status",true);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.pardo_icon)
            .setContentTitle("帕朵一号 · 工作状态").setContentText(detail)
            .setStyle(new Notification.BigTextStyle().bigText(detail)).setContentIntent(pending)
            .setOnlyAlertOnce(true).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID,notification);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onTimeout(int startId,int fgsType){
        // Android 15 limits a dataSync foreground service to six hours per day.
        MainActivity.pauseActiveWorkForSystemLimit();
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
}
