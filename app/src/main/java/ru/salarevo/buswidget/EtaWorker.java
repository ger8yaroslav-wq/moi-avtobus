package ru.salarevo.buswidget;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class EtaWorker extends Worker {
    public EtaWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        // Сразу перерисовываем виджет: истёкшее значение исчезнет ещё до ответа сети
        // и виджет не успеет показывать уже прошедшее время.
        AppPrefs.beginRefresh(context);
        BusWidgetProvider.renderAll(context);
        try {
            CityArrivalProvider.FetchResult result = CityArrivalProvider.fetch(context);
            AppPrefs.saveArrivals(context, result);
            RefreshScheduler.scheduleAtArrival(context, result.arrivals);
        } catch (CityArrivalProvider.FetchException error) {
            AppPrefs.saveError(context, safeMessage(error), error.diagnostic);
        } catch (Throwable error) {
            AppPrefs.saveError(context, safeMessage(error), error.toString());
        }
        BusWidgetProvider.renderAll(context);
        RefreshScheduler.scheduleNext(context);
        return Result.success();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? "Ошибка " + error.getClass().getSimpleName()
                : message;
    }
}
