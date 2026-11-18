package com.mybudget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class BudgetWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.load(ctx)
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.w_balance, Store.balance?.let { won(it) } ?: "—")
            val un = Store.unassigned().size
            v.setTextViewText(R.id.w_label, if (un > 0) "통장 잔액 · 미분류 ${un}건" else "통장 잔액")

            val svc = Intent(ctx, WidgetService::class.java)
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            v.setRemoteAdapter(R.id.w_list, svc)
            v.setEmptyView(R.id.w_list, R.id.w_empty)

            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            v.setPendingIntentTemplate(R.id.w_list,
                PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            mgr.updateAppWidget(id, v)
        }
        @Suppress("DEPRECATION")
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BudgetWidget::class.java))
            if (ids.isEmpty()) return
            BudgetWidget().onUpdate(ctx, mgr, ids)
        }

        fun levelColor(ctx: Context, remain: Long, amount: Long): Int {
            val ratio = if (amount > 0) remain.toDouble() / amount else 0.0
            return ctx.getColor(when {
                remain <= 0 || ratio <= 0.1 -> R.color.bad
                ratio <= 0.3 -> R.color.warn
                else -> R.color.good
            })
        }

        fun permille(remain: Long, amount: Long): Int =
            if (amount <= 0) 0 else (remain * 1000 / amount).coerceIn(0, 1000).toInt()
    }
}

class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Row(val name: String, val remain: Long, val amount: Long)

    private class Factory(val ctx: Context) : RemoteViewsFactory {
        private var rows = listOf<Row>()

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            Store.load(ctx)
            rows = synchronized(Store) { Store.budgets.map { Row(it.name, Store.remaining(it), it.amount) } }
        }

        override fun getCount() = rows.size

        override fun getViewAt(i: Int): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_row)
            val r = rows.getOrNull(i) ?: return v
            val color = BudgetWidget.levelColor(ctx, r.remain, r.amount)
            v.setTextViewText(R.id.wr_name, r.name)
            v.setTextViewText(R.id.wr_remain, won(r.remain))
            v.setTextColor(R.id.wr_remain, if (r.remain < 0) color else ctx.getColor(R.color.text))
            v.setProgressBar(R.id.wr_bar, 1000, BudgetWidget.permille(r.remain, r.amount), false)
            v.setColorStateList(R.id.wr_bar, "setProgressTintList", ColorStateList.valueOf(color))
            v.setViewVisibility(R.id.wr_bar, View.VISIBLE)
            v.setOnClickFillInIntent(R.id.wr_root, Intent())
            return v
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(i: Int) = i.toLong()
        override fun hasStableIds() = false
    }
}
