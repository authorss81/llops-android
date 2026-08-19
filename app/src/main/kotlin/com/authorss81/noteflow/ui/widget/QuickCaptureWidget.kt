package com.authorss81.noteflow.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.authorss81.noteflow.MainActivity
import com.authorss81.noteflow.R
import com.authorss81.noteflow.services.WidgetLaunchPolicy

/**
 * Phase 158 (deferred ROADMAP 22.5b) — the home-widget "New note"
 * quick-capture, shipped as a LAUNCHER SHORTCUT ONLY.
 *
 * Hard constraints honored here:
 *  - NO note content ever appears on the widget (icon + fixed label only);
 *  - NO vault access in the widget process — this provider only builds a
 *    RemoteViews whose single click launches [MainActivity] with the explicit
 *    [WidgetLaunchPolicy.EXTRA_QUICK_CAPTURE] boolean; MainActivity (already
 *    vault-gated by the LockScreen) turns it into a fresh note;
 *  - updatePeriodMillis = 0 — there is no periodic refresh with content to
 *    avoid (nothing is ever read), and nothing to keep cheap;
 *  - no new permission is required (the receiver is NOT exported; the launcher
 *    binds it through the appwidget manager, not an exported intent).
 */
class QuickCaptureWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context))
        }
    }

    private fun buildRemoteViews(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_quick_capture).apply {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                // singleTask + CLEAR_TOP: a tap while the app is already open
                // re-delivers to the existing task instead of stacking.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(WidgetLaunchPolicy.EXTRA_QUICK_CAPTURE, true)
            }
            val pending = PendingIntent.getActivity(
                context,
                WidgetLaunchPolicy.WIDGET_PENDING_INTENT_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setOnClickPendingIntent(R.id.widget_quick_capture_root, pending)
        }
}
