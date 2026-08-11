package ru.salarevo.buswidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;
import java.util.Map;

final class RefreshScheduler {
    private static final String PERIODIC_NAME = "bus-arrivals-periodic-v2";
    private static final String IMMEDIATE_NAME = "bus-arrivals-now-v2";
    private static final String NEXT_NAME = "bus-arrivals-next-v2";
    private static final String ARRIVAL_NAME = "bus-arrivals-at-arrival-v2";
    private static final int MINUTE_TICK_REQUEST = 43;

    private RefreshScheduler() {}

    static void schedule(Context context) {
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                EtaWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic);
    }

    static void refreshNow(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(EtaWorker.class)
                .setConstraints(networkConstraints())
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    static void scheduleNext(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(EtaWorker.class)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                NEXT_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    static void scheduleAtArrival(Context context, Map<String, CityArrivalProvider.Arrival> arrivals) {
        long now = System.currentTimeMillis();
        long earliest = Long.MAX_VALUE;
        for (CityArrivalProvider.Arrival arrival : arrivals.values()) {
            if (arrival != null && arrival.epochMs > now && arrival.epochMs < earliest) {
                earliest = arrival.epochMs;
            }
        }
        if (earliest == Long.MAX_VALUE) return;

        // AlarmManager быстро убирает истёкший таймер, а WorkManager надёжно
        // повторяет запрос с учётом фоновых ограничений конкретного телефона.
        long triggerAt = earliest + 1_000L;
        Intent intent = new Intent(context, BusWidgetProvider.class)
                .setAction(BusWidgetProvider.ACTION_ARRIVAL_REACHED);
        PendingIntent pending = PendingIntent.getBroadcast(context, 42, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) {
            alarm.cancel(pending);
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        }

        long delay = Math.max(1_000L, triggerAt - now);
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(EtaWorker.class)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(networkConstraints())
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                ARRIVAL_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    static void scheduleMinuteTick(Context context) {
        long now = System.currentTimeMillis();
        long delay = Long.MAX_VALUE;
        for (RouteConfig config : ConfigurationStore.load(context)) {
            long eta = AppPrefs.arrival(context, config.id);
            long nextEta = AppPrefs.nextArrival(context, config.id);
            for (long candidate : new long[]{eta, nextEta}) {
                if (candidate <= now + 5_000L) continue;
                long remaining = candidate - now;
                long minutes = Math.max(1L, (remaining + 59_999L) / 60_000L);
                long untilChange = remaining - Math.max(0L, minutes - 1L) * 60_000L;
                delay = Math.min(delay, Math.max(1_000L, untilChange + 250L));
            }
        }
        Intent intent = new Intent(context, BusWidgetProvider.class)
                .setAction(BusWidgetProvider.ACTION_MINUTE_TICK);
        PendingIntent pending = PendingIntent.getBroadcast(context, MINUTE_TICK_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        alarm.cancel(pending);
        if (delay != Long.MAX_VALUE) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + delay, pending);
        }
    }

    static void cancelMinuteTick(Context context) {
        Intent intent = new Intent(context, BusWidgetProvider.class)
                .setAction(BusWidgetProvider.ACTION_MINUTE_TICK);
        PendingIntent pending = PendingIntent.getBroadcast(context, MINUTE_TICK_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) alarm.cancel(pending);
    }

    private static Constraints networkConstraints() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }
}
