package dev.retrobit.assistant.launch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import dev.retrobit.assistant.MainActivity
import dev.retrobit.assistant.R

const val ACTION_LISTEN = "dev.retrobit.assistant.LISTEN"

fun listenIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(ACTION_LISTEN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

private fun listenPendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context, 1, listenIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

/** Tile Quick Settings: tarik panel notifikasi → ketuk "Rbit: bicara". */
class ListenTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(listenPendingIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(listenIntent(this))
        }
    }
}

/** Widget 1×1 di layar utama: satu ketukan untuk bicara. */
class ListenWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_listen)
        views.setOnClickPendingIntent(R.id.widget_root, listenPendingIntent(context))
        for (id in ids) manager.updateAppWidget(id, views)
    }
}
