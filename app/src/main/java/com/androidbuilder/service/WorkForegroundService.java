package com.androidbuilder.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.androidbuilder.R;
import com.androidbuilder.ui.MainActivity;
import com.androidbuilder.ui.ProjectActivity;

import java.util.HashSet;
import java.util.Set;

public class WorkForegroundService extends Service {
    private static final String ACTION_BEGIN = "com.androidbuilder.action.WORK_BEGIN";
    private static final String ACTION_END = "com.androidbuilder.action.WORK_END";
    private static final String EXTRA_PROJECT_ID = "project_id";
    private static final String EXTRA_MESSAGE = "message";
    private static final String CHANNEL_ID = "androidbuilder_work";
    private static final int NOTIFICATION_ID = 1001;

    private final Set<Long> activeProjects = new HashSet<>();
    private String currentMessage = "";

    public static void begin(Context context, long projectId, String message) {
        Intent intent = new Intent(context, WorkForegroundService.class);
        intent.setAction(ACTION_BEGIN);
        intent.putExtra(EXTRA_PROJECT_ID, projectId);
        intent.putExtra(EXTRA_MESSAGE, message);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void end(Context context, long projectId) {
        Intent intent = new Intent(context, WorkForegroundService.class);
        intent.setAction(ACTION_END);
        intent.putExtra(EXTRA_PROJECT_ID, projectId);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            startForeground(NOTIFICATION_ID, notification());
            return START_NOT_STICKY;
        }
        long projectId = intent.getLongExtra(EXTRA_PROJECT_ID, -1);
        if (ACTION_BEGIN.equals(intent.getAction()) && projectId >= 0) {
            activeProjects.add(projectId);
            currentMessage = intent.getStringExtra(EXTRA_MESSAGE);
            startForeground(NOTIFICATION_ID, notification());
        } else if (ACTION_END.equals(intent.getAction()) && projectId >= 0) {
            activeProjects.remove(projectId);
            if (activeProjects.isEmpty()) {
                stopForeground(true);
                stopSelf();
            } else {
                startForeground(NOTIFICATION_ID, notification());
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        String message = currentMessage == null || currentMessage.trim().isEmpty()
                ? getString(R.string.foreground_work_content)
                : currentMessage;
        long projectId = activeProjectId();
        Intent openIntent = new Intent(this, notificationTargetActivity(projectId));
        if (projectId >= 0) {
            openIntent.putExtra(MainActivity.EXTRA_PROJECT_ID, projectId);
        }
        openIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                pendingIntentRequestCode(projectId),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_build)
                .setContentTitle(getString(R.string.foreground_work_title))
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    static Class<?> notificationTargetActivity(long projectId) {
        return projectId >= 0 ? ProjectActivity.class : MainActivity.class;
    }

    private int pendingIntentRequestCode(long projectId) {
        return projectId >= 0 ? (int) (10000 + Math.min(projectId, Integer.MAX_VALUE - 10000L)) : 0;
    }

    private long activeProjectId() {
        if (activeProjects.isEmpty()) {
            return -1;
        }
        return activeProjects.iterator().next();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.foreground_work_channel),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
