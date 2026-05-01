package com.example.llamadroid.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.OrganizerEventEntity
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale

private const val CALENDAR_WIDGET_PREFS_NAME = "organizer_calendar_widgets"
private const val PREF_MONTH_PREFIX = "month_"
private const val ACTION_CALENDAR_MONTH_DELTA = "com.example.llamadroid.widget.CALENDAR_MONTH_DELTA"
private const val EXTRA_CALENDAR_MONTH_DELTA = "extra_calendar_month_delta"
private val WIDGET_EVENT_BUBBLE_BACKGROUNDS = intArrayOf(
    R.drawable.widget_upcoming_bubble_blue,
    R.drawable.widget_upcoming_bubble_green,
    R.drawable.widget_upcoming_bubble_purple,
    R.drawable.widget_upcoming_bubble_gold,
    R.drawable.widget_upcoming_bubble_rose
)

private object WidgetIntents {
    private const val REQUEST_OPEN_BASE = 100_000
    private const val REQUEST_MONTH_BASE = 300_000

    fun openRoute(context: Context, route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_BASE + requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun calendarMonth(context: Context, appWidgetId: Int, delta: Int): PendingIntent {
        val intent = Intent(context, OrganizerCalendarWidgetProvider::class.java).apply {
            action = ACTION_CALENDAR_MONTH_DELTA
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(EXTRA_CALENDAR_MONTH_DELTA, delta)
        }
        val directionSlot = if (delta < 0) 0 else 1
        return PendingIntent.getBroadcast(
            context,
            REQUEST_MONTH_BASE + (appWidgetId * 2) + directionSlot,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

private object OrganizerCalendarWidgetPrefs {
    fun setMonth(context: Context, appWidgetId: Int, month: YearMonth) {
        context.getSharedPreferences(CALENDAR_WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("$PREF_MONTH_PREFIX$appWidgetId", month.toString())
            .apply()
    }

    fun getMonth(context: Context, appWidgetId: Int): YearMonth? =
        context.getSharedPreferences(CALENDAR_WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            .getString("$PREF_MONTH_PREFIX$appWidgetId", null)
            ?.let { raw -> runCatching { YearMonth.parse(raw) }.getOrNull() }

    fun remove(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(CALENDAR_WIDGET_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("$PREF_MONTH_PREFIX$appWidgetId")
            .apply()
    }
}

class OrganizerCalendarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context.applicationContext, appWidgetManager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_CALENDAR_MONTH_DELTA) return

        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val zone = ZoneId.systemDefault()
        val delta = intent.getIntExtra(EXTRA_CALENDAR_MONTH_DELTA, 0)
        val currentMonth = OrganizerCalendarWidgetPrefs.getMonth(context, appWidgetId) ?: YearMonth.now(zone)
        OrganizerCalendarWidgetPrefs.setMonth(context, appWidgetId, currentMonth.plusMonths(delta.toLong()))
        updateWidget(context.applicationContext, AppWidgetManager.getInstance(context), appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { OrganizerCalendarWidgetPrefs.remove(context, it) }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val weekdayIds = intArrayOf(
            R.id.widget_weekday_1,
            R.id.widget_weekday_2,
            R.id.widget_weekday_3,
            R.id.widget_weekday_4,
            R.id.widget_weekday_5,
            R.id.widget_weekday_6,
            R.id.widget_weekday_7
        )
        private val dayIds = intArrayOf(
            R.id.widget_day_1,
            R.id.widget_day_2,
            R.id.widget_day_3,
            R.id.widget_day_4,
            R.id.widget_day_5,
            R.id.widget_day_6,
            R.id.widget_day_7,
            R.id.widget_day_8,
            R.id.widget_day_9,
            R.id.widget_day_10,
            R.id.widget_day_11,
            R.id.widget_day_12,
            R.id.widget_day_13,
            R.id.widget_day_14,
            R.id.widget_day_15,
            R.id.widget_day_16,
            R.id.widget_day_17,
            R.id.widget_day_18,
            R.id.widget_day_19,
            R.id.widget_day_20,
            R.id.widget_day_21,
            R.id.widget_day_22,
            R.id.widget_day_23,
            R.id.widget_day_24,
            R.id.widget_day_25,
            R.id.widget_day_26,
            R.id.widget_day_27,
            R.id.widget_day_28,
            R.id.widget_day_29,
            R.id.widget_day_30,
            R.id.widget_day_31,
            R.id.widget_day_32,
            R.id.widget_day_33,
            R.id.widget_day_34,
            R.id.widget_day_35,
            R.id.widget_day_36,
            R.id.widget_day_37,
            R.id.widget_day_38,
            R.id.widget_day_39,
            R.id.widget_day_40,
            R.id.widget_day_41,
            R.id.widget_day_42
        )

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, OrganizerCalendarWidgetProvider::class.java))
            ids.forEach { updateWidget(context.applicationContext, manager, it) }
            OrganizerUpcomingEventsWidgetProvider.refreshAll(context)
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            scope.launch {
                val zone = ZoneId.systemDefault()
                val month = OrganizerCalendarWidgetPrefs.getMonth(context, appWidgetId) ?: YearMonth.now(zone)
                val rangeStart = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val rangeEnd = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                val events = AppDatabase.getDatabase(context)
                    .organizerDao()
                    .getEventsInRangeOnce(rangeStart, rangeEnd)
                val views = RemoteViews(context.packageName, R.layout.widget_organizer_calendar)
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_calendar_title))
                views.setTextViewText(R.id.widget_month_label, formatWidgetMonth(month))
                views.setTextViewText(
                    R.id.widget_calendar_upcoming_title,
                    context.getString(R.string.widget_calendar_upcoming_title)
                )
                views.setOnClickPendingIntent(
                    R.id.widget_root,
                    WidgetIntents.openRoute(context, Screen.NotesManager.route, appWidgetId)
                )
                views.setOnClickPendingIntent(
                    R.id.widget_previous_month,
                    WidgetIntents.calendarMonth(context, appWidgetId, -1)
                )
                views.setOnClickPendingIntent(
                    R.id.widget_next_month,
                    WidgetIntents.calendarMonth(context, appWidgetId, 1)
                )
                fillMonthGrid(context, views, month, events, zone)
                val adapterIntent = Intent(context, OrganizerCalendarUpcomingEventsService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    data = Uri.parse("llamadroid://calendar-upcoming/$appWidgetId")
                }
                views.setRemoteAdapter(R.id.widget_calendar_upcoming_list, adapterIntent)
                views.setEmptyView(R.id.widget_calendar_upcoming_list, R.id.widget_calendar_upcoming_empty)
                views.setTextViewText(R.id.widget_calendar_upcoming_empty, context.getString(R.string.widget_calendar_empty))
                appWidgetManager.updateAppWidget(appWidgetId, views)
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_calendar_upcoming_list)
            }
        }

        private fun fillMonthGrid(
            context: Context,
            views: RemoteViews,
            month: YearMonth,
            events: List<OrganizerEventEntity>,
            zone: ZoneId
        ) {
            val locale = Locale.getDefault()
            val firstDayOfWeek = DayOfWeek.MONDAY
            val dayOrder = orderedWeekdays(firstDayOfWeek)
            val weekdayFormatter = DateTimeFormatter.ofPattern("EEEEE", locale)
            dayOrder.forEachIndexed { index, dayOfWeek ->
                val labelDate = LocalDate.of(2024, 1, 1).with(TemporalAdjusters.nextOrSame(dayOfWeek))
                views.setTextViewText(weekdayIds[index], weekdayFormatter.format(labelDate))
            }

            val monthStart = month.atDay(1)
            val today = LocalDate.now(zone)
            val firstOffset = (monthStart.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
            val gridStart = monthStart.minusDays(firstOffset.toLong())
            val eventsByDate = eventsByDate(events, month, zone)

            dayIds.forEachIndexed { index, viewId ->
                val date = gridStart.plusDays(index.toLong())
                val inMonth = YearMonth.from(date) == month
                val dayEvents = eventsByDate[date].orEmpty()
                if (!inMonth) {
                    views.setTextViewText(viewId, "")
                    views.setTextColor(viewId, Color.TRANSPARENT)
                    views.setInt(viewId, "setBackgroundResource", R.drawable.widget_day_cell)
                } else {
                    views.setTextViewText(viewId, calendarCellText(context, date, dayEvents))
                    views.setTextColor(viewId, Color.parseColor("#F5F7FF"))
                    views.setInt(
                        viewId,
                        "setBackgroundResource",
                        when {
                            date == today -> R.drawable.widget_day_today
                            dayEvents.isNotEmpty() -> R.drawable.widget_day_event
                            else -> R.drawable.widget_day_cell
                        }
                    )
                }
            }
        }

        private fun orderedWeekdays(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
            (0 until 7).map { firstDayOfWeek.plus(it.toLong()) }

        private fun eventsByDate(
            events: List<OrganizerEventEntity>,
            month: YearMonth,
            zone: ZoneId
        ): Map<LocalDate, List<OrganizerEventEntity>> {
            val monthStart = month.atDay(1)
            val monthEnd = month.atEndOfMonth()
            val grouped = linkedMapOf<LocalDate, MutableList<OrganizerEventEntity>>()
            events.forEach { event ->
                val eventStart = Instant.ofEpochMilli(event.startAtMillis).atZone(zone).toLocalDate()
                val eventEnd = Instant.ofEpochMilli(event.endAtMillis ?: event.startAtMillis).atZone(zone).toLocalDate()
                var date = maxOf(eventStart, monthStart)
                val lastDate = minOf(eventEnd, monthEnd)
                while (!date.isAfter(lastDate)) {
                    grouped.getOrPut(date) { mutableListOf() }.add(event)
                    date = date.plusDays(1)
                }
            }
            return grouped
        }

        private fun calendarCellText(
            context: Context,
            date: LocalDate,
            events: List<OrganizerEventEntity>
        ): String {
            if (events.isEmpty()) return date.dayOfMonth.toString()
            val eventLine = if (events.size == 1) {
                context.getString(R.string.widget_calendar_day_event_one, events.first().title.take(14))
            } else {
                context.getString(R.string.widget_calendar_day_event_count, events.size)
            }
            return "${date.dayOfMonth}\n$eventLine"
        }
    }
}

class OrganizerCalendarUpcomingEventsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        OrganizerCalendarUpcomingEventsFactory(applicationContext)
}

private class OrganizerCalendarUpcomingEventsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {
    private var events: List<OrganizerEventEntity> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        events = runBlocking {
            AppDatabase.getDatabase(context)
                .organizerDao()
                .getAllEventsOnce()
                .filter { (it.endAtMillis ?: it.startAtMillis) >= System.currentTimeMillis() }
                .sortedBy { it.startAtMillis }
        }
    }

    override fun onDestroy() {
        events = emptyList()
    }

    override fun getCount(): Int = events.size

    override fun getViewAt(position: Int): RemoteViews {
        val event = events.getOrNull(position)
        return RemoteViews(context.packageName, R.layout.widget_calendar_upcoming_item).apply {
            if (event == null) {
                setTextViewText(R.id.widget_calendar_upcoming_item, "")
                setInt(R.id.widget_calendar_upcoming_item, "setBackgroundResource", R.drawable.widget_upcoming_bubble_empty)
            } else {
                setTextViewText(
                    R.id.widget_calendar_upcoming_item,
                    context.getString(
                        R.string.widget_calendar_event_line,
                        formatWidgetDate(event.startAtMillis),
                        event.title
                    )
                )
                setInt(
                    R.id.widget_calendar_upcoming_item,
                    "setBackgroundResource",
                    WIDGET_EVENT_BUBBLE_BACKGROUNDS[position % WIDGET_EVENT_BUBBLE_BACKGROUNDS.size]
                )
            }
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = events.getOrNull(position)?.id ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}

class OrganizerUpcomingEventsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context.applicationContext, appWidgetManager, it) }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, OrganizerUpcomingEventsWidgetProvider::class.java))
            ids.forEach { updateWidget(context.applicationContext, manager, it) }
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            scope.launch {
                val events = AppDatabase.getDatabase(context)
                    .organizerDao()
                    .getAllEventsOnce()
                    .filter { (it.endAtMillis ?: it.startAtMillis) >= System.currentTimeMillis() }
                    .sortedBy { it.startAtMillis }
                    .take(5)
                val views = RemoteViews(context.packageName, R.layout.widget_organizer_upcoming)
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_calendar_upcoming_title))
                views.setOnClickPendingIntent(
                    R.id.widget_root,
                    WidgetIntents.openRoute(context, Screen.NotesManager.route, appWidgetId)
                )
                fillCalendarLines(
                    context = context,
                    views = views,
                    events = events,
                    lineIds = listOf(
                        R.id.widget_line_1,
                        R.id.widget_line_2,
                        R.id.widget_line_3,
                        R.id.widget_line_4,
                        R.id.widget_line_5
                    )
                )
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}

private fun fillCalendarLines(
    context: Context,
    views: RemoteViews,
    events: List<OrganizerEventEntity>,
    lineIds: List<Int>
) {
    if (events.isEmpty()) {
        views.setViewVisibility(lineIds.first(), View.VISIBLE)
        views.setTextViewText(lineIds.first(), context.getString(R.string.widget_calendar_empty))
        views.setInt(lineIds.first(), "setBackgroundResource", R.drawable.widget_upcoming_bubble_empty)
        lineIds.drop(1).forEach {
            views.setTextViewText(it, "")
            views.setViewVisibility(it, View.GONE)
        }
        return
    }
    events.take(lineIds.size).forEachIndexed { index, event ->
        val lineId = lineIds[index]
        views.setViewVisibility(lineId, View.VISIBLE)
        views.setTextViewText(
            lineId,
            context.getString(
                R.string.widget_calendar_event_line,
                formatWidgetDate(event.startAtMillis),
                event.title
            )
        )
        views.setInt(
            lineId,
            "setBackgroundResource",
            WIDGET_EVENT_BUBBLE_BACKGROUNDS[index % WIDGET_EVENT_BUBBLE_BACKGROUNDS.size]
        )
    }
    lineIds.drop(events.size.coerceAtMost(lineIds.size)).forEach {
        views.setTextViewText(it, "")
        views.setViewVisibility(it, View.GONE)
    }
}

private fun formatWidgetMonth(yearMonth: YearMonth): String =
    DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()).format(yearMonth)

private fun formatWidgetDate(epochMillis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMillis))
