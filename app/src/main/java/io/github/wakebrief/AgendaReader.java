package io.github.wakebrief;

import android.content.Context;
import android.database.Cursor;
import android.provider.CalendarContract;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Today's events from every calendar synced to the phone (Google Calendar, Outlook, OnePlus
 * calendar, birthdays...), read straight from Android's calendar database — works offline.
 */
final class AgendaReader {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private AgendaReader() {}

    static final class Event {
        final String title;
        final String location;
        final long begin, end;
        final boolean allDay;

        Event(String title, String location, long begin, long end, boolean allDay) {
            this.title = title;
            this.location = location;
            this.begin = begin;
            this.end = end;
            this.allDay = allDay;
        }
    }

    /** Events for the rest of today, all-day first. Throws SecurityException without permission. */
    static List<Event> today(Context ctx) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long dayStart = cal.getTimeInMillis();
        cal.add(Calendar.DAY_OF_MONTH, 1);
        long dayEnd = cal.getTimeInMillis();
        long now = System.currentTimeMillis();

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(dayStart));
        // All-day events are stored as UTC midnights.
        SimpleDateFormat utcDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        utcDate.setTimeZone(TimeZone.getTimeZone("UTC"));

        String[] proj = {
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.VISIBLE,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                CalendarContract.Instances.STATUS,
        };

        List<Event> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // Query one extra day on each side so UTC-stored all-day events are never missed.
        try (Cursor c = CalendarContract.Instances.query(
                ctx.getContentResolver(), proj, dayStart - DAY_MS, dayEnd + DAY_MS)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                String title = c.isNull(0) ? "" : c.getString(0).trim();
                long begin = c.getLong(1);
                long end = c.getLong(2);
                boolean allDay = c.getInt(3) == 1;
                String location = c.isNull(4) ? "" : c.getString(4).trim();
                boolean visible = c.isNull(5) || c.getInt(5) == 1;
                int self = c.isNull(6) ? -1 : c.getInt(6);
                int status = c.isNull(7) ? -1 : c.getInt(7);

                if (!visible) continue;
                if (self == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue;
                if (status == CalendarContract.Events.STATUS_CANCELED) continue;

                if (allDay) {
                    String b = utcDate.format(new Date(begin));
                    String e = utcDate.format(new Date(end));
                    if (b.compareTo(today) > 0 || e.compareTo(today) <= 0) continue;
                } else {
                    if (begin >= dayEnd || end <= now) continue;
                }
                if (!seen.add(title + "|" + begin)) continue; // same event in two calendars
                out.add(new Event(title, location, begin, end, allDay));
            }
        }
        Collections.sort(out, (a, b) -> {
            if (a.allDay != b.allDay) return a.allDay ? -1 : 1;
            return Long.compare(a.begin, b.begin);
        });
        return out;
    }
}
