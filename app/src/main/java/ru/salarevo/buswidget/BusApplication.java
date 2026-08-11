package ru.salarevo.buswidget;

import android.app.Application;

public class BusApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                String message = error.getClass().getSimpleName();
                if (error.getStackTrace().length > 0) {
                    StackTraceElement where = error.getStackTrace()[0];
                    message += " в " + where.getClassName() + ":" + where.getLineNumber();
                }
                AppPrefs.saveCrash(this, message);
            } catch (Throwable ignored) {
                // Не мешаем Android сформировать стандартный отчёт об аварии.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}
