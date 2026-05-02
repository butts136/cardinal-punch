package com.infopunch.checker.hours;

import com.infopunch.checker.SessionManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.HttpUrl;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class HoursRepository {
    private static final DateTimeFormatter RANGE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.CANADA_FRENCH);
    private static final DateTimeFormatter PORTAL_DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.CANADA_FRENCH);
    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .cookieJar(new JavaNetCookieJar(cookieManager))
            .build();
    private final Map<String, HoursModels.WeekData> cache = new HashMap<>();

    public HoursModels.WeekData loadCurrentWeek(SessionManager.SessionData session) throws IOException {
        String url = buildConsultMobileUrl(session);
        return fetchWeek(session, url);
    }

    public HoursModels.WeekData loadRelativeWeek(SessionManager.SessionData session, String relativeUrl) throws IOException {
        if (relativeUrl == null || relativeUrl.trim().isEmpty()) {
            return null;
        }
        return fetchWeek(session, buildAbsoluteUrl(session.sitePath, relativeUrl));
    }

    public HoursModels.WeekData loadWeekForDate(SessionManager.SessionData session, LocalDate targetDate) throws IOException {
        HoursModels.WeekData week = loadCurrentWeek(session);
        int guard = 0;
        while (week != null && guard < 16) {
            if (containsDate(week, targetDate)) {
                return week;
            }
            if (week.weekStart != null && targetDate.isBefore(week.weekStart)) {
                if (week.previousWeekRelativeUrl == null || week.previousWeekRelativeUrl.trim().isEmpty()) {
                    return week;
                }
                week = loadRelativeWeek(session, week.previousWeekRelativeUrl);
            } else {
                if (week.nextWeekRelativeUrl == null || week.nextWeekRelativeUrl.trim().isEmpty()) {
                    return week;
                }
                week = loadRelativeWeek(session, week.nextWeekRelativeUrl);
            }
            guard++;
        }
        return week;
    }

    public HoursModels.MonthTotals computeMonthTotals(SessionManager.SessionData session, HoursModels.WeekData anchorWeek) throws IOException {
        HoursModels.MonthTotals totals = new HoursModels.MonthTotals();
        totals.month = determinePrimaryMonth(anchorWeek);

        Map<String, Boolean> visited = new HashMap<>();
        accumulateMonth(session, anchorWeek, totals, visited);

        String prev = anchorWeek.previousWeekRelativeUrl;
        while (prev != null && !prev.isEmpty()) {
            HoursModels.WeekData week = loadRelativeWeek(session, prev);
            if (week == null || !intersectsMonth(week, totals.month) || visited.containsKey(prev)) {
                break;
            }
            accumulateMonth(session, week, totals, visited);
            prev = week.previousWeekRelativeUrl;
        }

        String next = anchorWeek.nextWeekRelativeUrl;
        while (next != null && !next.isEmpty()) {
            HoursModels.WeekData week = loadRelativeWeek(session, next);
            if (week == null || !intersectsMonth(week, totals.month) || visited.containsKey(next)) {
                break;
            }
            accumulateMonth(session, week, totals, visited);
            next = week.nextWeekRelativeUrl;
        }

        return totals;
    }

    public String sendNote(SessionManager.SessionData session, LocalDate date, String note) throws IOException {
        String referer = ensurePortalSession(session);
        String endpoint = buildAbsoluteUrl(session.sitePath, "ajaxCommands/UserPanel.AddNotesEmp.asp");
        IOException lastError = null;
        String lastBody = "";

        for (String dateCandidate : buildDateCandidates(date)) {
            HttpUrl url = HttpUrl.parse(endpoint).newBuilder()
                    .addQueryParameter("DateJour", dateCandidate)
                    .addQueryParameter("QRaison", note)
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "*/*")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", referer)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string().trim() : "";
                lastBody = body;
                if (!response.isSuccessful()) {
                    lastError = new IOException("Envoi de note impossible (" + response.code() + ")");
                    continue;
                }
                if (body.isEmpty() || body.toLowerCase(Locale.ROOT).contains("note")) {
                    return body;
                }
                return body;
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IOException(lastBody.isEmpty() ? "Envoi de note impossible." : lastBody);
    }

    public List<String> sendNoteTestVariants(SessionManager.SessionData session, LocalDate date, String note) throws IOException {
        String referer = ensurePortalSession(session);
        String endpoint = buildAbsoluteUrl(session.sitePath, "ajaxCommands/UserPanel.AddNotesEmp.asp");
        List<NoteVariant> variants = buildNoteVariants(date, note);
        List<String> results = new ArrayList<>();

        for (int i = 0; i < variants.size(); i++) {
            NoteVariant variant = variants.get(i);
            String testNote = "[NOTE] #" + (i + 1) + " " + note;
            try {
                String response = sendNoteVariant(endpoint, referer, variant, testNote);
                results.add("#" + (i + 1) + " OK " + response);
            } catch (Exception exception) {
                results.add("#" + (i + 1) + " ECHEC " + (exception.getMessage() == null ? "" : exception.getMessage()));
            }
        }
        return results;
    }

    private String sendNoteVariant(String endpoint, String referer, NoteVariant variant, String note) throws IOException {
        Request request;
        if (variant.post) {
            RequestBody body = new FormBody.Builder()
                    .add(variant.dateKey, variant.dateValue)
                    .add(variant.noteKey, note)
                    .build();
            request = new Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .header("Accept", "*/*")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", referer)
                    .build();
        } else {
            HttpUrl url = HttpUrl.parse(endpoint).newBuilder()
                    .addQueryParameter(variant.dateKey, variant.dateValue)
                    .addQueryParameter(variant.noteKey, note)
                    .build();
            request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "*/*")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", referer)
                    .build();
        }

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string().trim() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            return body;
        }
    }

    private List<NoteVariant> buildNoteVariants(LocalDate date, String note) {
        List<String> dates = buildDateCandidates(date);
        List<NoteVariant> variants = new ArrayList<>();
        variants.add(new NoteVariant(false, "DateJour", dates.get(0), "QRaison"));
        variants.add(new NoteVariant(false, "DateJour", dates.get(1), "QRaison"));
        variants.add(new NoteVariant(false, "DateJour", dates.get(2), "QRaison"));
        variants.add(new NoteVariant(true, "DateJour", dates.get(0), "QRaison"));
        variants.add(new NoteVariant(true, "DateJour", dates.get(1), "QRaison"));
        variants.add(new NoteVariant(true, "DateJour", dates.get(2), "QRaison"));
        variants.add(new NoteVariant(false, "dateJour", dates.get(0), "qRaison"));
        variants.add(new NoteVariant(false, "DateJour", dates.get(0), "Raison"));
        variants.add(new NoteVariant(true, "dateJour", dates.get(0), "qRaison"));
        variants.add(new NoteVariant(true, "DateJour", dates.get(0), "Raison"));
        return variants;
    }

    private void accumulateMonth(
            SessionManager.SessionData session,
            HoursModels.WeekData week,
            HoursModels.MonthTotals totals,
            Map<String, Boolean> visited
    ) {
        String key = week.currentRangeLabel == null ? String.valueOf(week.hashCode()) : week.currentRangeLabel;
        if (visited.containsKey(key)) {
            return;
        }
        visited.put(key, true);

        for (HoursModels.DayEntry day : week.days) {
            if (day.date != null && YearMonth.from(day.date).equals(totals.month)) {
                totals.regularMinutes += day.getRegularMinutes();
                totals.overtimeMinutes += day.getOvertimeMinutes();
            }
        }
    }

    private boolean containsDate(HoursModels.WeekData week, LocalDate targetDate) {
        if (week == null || targetDate == null) {
            return false;
        }
        if (week.weekStart != null && week.weekEnd != null) {
            return !targetDate.isBefore(week.weekStart) && !targetDate.isAfter(week.weekEnd);
        }
        for (HoursModels.DayEntry day : week.days) {
            if (targetDate.equals(day.date)) {
                return true;
            }
        }
        return false;
    }

    private boolean intersectsMonth(HoursModels.WeekData week, YearMonth month) {
        if (week == null || week.weekStart == null || week.weekEnd == null) {
            return false;
        }
        return !week.weekStart.isAfter(month.atEndOfMonth()) && !week.weekEnd.isBefore(month.atDay(1));
    }

    private YearMonth determinePrimaryMonth(HoursModels.WeekData week) {
        Map<YearMonth, Integer> counts = new HashMap<>();
        for (HoursModels.DayEntry day : week.days) {
            if (day.date == null) {
                continue;
            }
            YearMonth ym = YearMonth.from(day.date);
            counts.put(ym, counts.getOrDefault(ym, 0) + 1);
        }
        YearMonth selected = YearMonth.from(week.weekStart);
        int max = -1;
        for (Map.Entry<YearMonth, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                selected = entry.getKey();
            }
        }
        return selected;
    }

    private HoursModels.WeekData fetchWeek(SessionManager.SessionData session, String absoluteUrl) throws IOException {
        if (cache.containsKey(absoluteUrl)) {
            return cache.get(absoluteUrl);
        }

        Request request = new Request.Builder().url(absoluteUrl).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Chargement des heures impossible (" + response.code() + ")");
            }
            String html = response.body() != null ? response.body().string() : "";
            HoursModels.WeekData weekData = parseWeekHtml(html);
            cache.put(absoluteUrl, weekData);
            return weekData;
        }
    }

    private HoursModels.WeekData parseWeekHtml(String html) {
        Document document = Jsoup.parse(html);
        HoursModels.WeekData weekData = new HoursModels.WeekData();

        Element rangeElement = document.selectFirst(".nav-semaine-mois span");
        weekData.currentRangeLabel = rangeElement != null ? rangeElement.text().trim() : "";
        List<LocalDate> weekDates = extractWeekDates(weekData.currentRangeLabel);
        if (!weekDates.isEmpty()) {
            weekData.weekStart = weekDates.get(0);
            weekData.weekEnd = weekDates.get(weekDates.size() - 1);
        }

        Element prev = document.selectFirst(".nav-semaine-mois a.prev");
        Element next = document.selectFirst(".nav-semaine-mois a.next");
        weekData.previousWeekRelativeUrl = prev != null ? prev.attr("href") : "";
        weekData.nextWeekRelativeUrl = next != null ? next.attr("href") : "";
        weekData.title = textOrEmpty(document.selectFirst("tbody.role-body.mes-heures .nom"));
        weekData.currentBankHours = textOrEmpty(document.selectFirst(".current-week .mal b"));

        List<String> currentSummary = extractSummary(document.select(".current-week .temps-wrapper > div"));
        if (currentSummary.size() >= 3) {
            weekData.currentWeekRegular = currentSummary.get(0);
            weekData.currentWeekOvertime = currentSummary.get(1);
            weekData.currentWeekTotal = currentSummary.get(2);
        }

        List<String> lastSummary = extractSummary(document.select(".last-week .temps-wrapper > div"));
        if (lastSummary.size() >= 3) {
            weekData.lastWeekRegular = lastSummary.get(0);
            weekData.lastWeekOvertime = lastSummary.get(1);
            weekData.lastWeekTotal = lastSummary.get(2);
        }

        Elements weekdayHeaders = document.select("tr.schedule-table__header th.day");
        Elements dayCells = document.select("tbody.role-body.mes-heures tr.extra-row td.calendar_day");
        List<String> weekdayNames = new ArrayList<>();
        for (Element header : weekdayHeaders) {
            weekdayNames.add(textOrEmpty(header.selectFirst(".weekday")));
        }

        for (int i = 0; i < dayCells.size(); i++) {
            Element cell = dayCells.get(i);
            HoursModels.DayEntry dayEntry = new HoursModels.DayEntry();
            dayEntry.dayLabel = textOrEmpty(cell.selectFirst("p.day"));
            dayEntry.weekday = i < weekdayNames.size() ? weekdayNames.get(i) : "";
            if (i < weekDates.size()) {
                dayEntry.date = weekDates.get(i);
            }

            Elements shiftSpans = cell.select(".time-cell.temps span");
            for (Element shift : shiftSpans) {
                dayEntry.shifts.add(shift.text().trim());
            }

            dayEntry.regularHours = extractNestedSpanText(cell.selectFirst(".time-cell.temps-reg"));
            dayEntry.overtimeHours = extractNestedSpanText(cell.selectFirst(".time-cell.temps-supp"));
            weekData.days.add(dayEntry);
        }

        return weekData;
    }

    private String ensurePortalSession(SessionManager.SessionData session) throws IOException {
        String url = buildConsultMobileUrl(session);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "text/html,application/xhtml+xml")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Connexion au portail impossible (" + response.code() + ")");
            }
            return response.request().url().toString();
        }
    }

    private List<String> buildDateCandidates(LocalDate date) {
        Set<String> values = new LinkedHashSet<>();
        values.add(date.format(PORTAL_DATE_FORMAT));
        values.add(date.toString());
        values.add(date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.CANADA_FRENCH)));
        return new ArrayList<>(values);
    }

    private List<String> extractSummary(Elements blocks) {
        List<String> values = new ArrayList<>();
        for (Element block : blocks) {
            values.add(textOrEmpty(block.selectFirst(".temps")));
        }
        return values;
    }

    private String extractNestedSpanText(Element element) {
        if (element == null) {
            return "00:00";
        }
        Element span = element.selectFirst("span");
        return span != null ? span.text().trim() : "00:00";
    }

    private List<LocalDate> extractWeekDates(String rangeLabel) {
        List<LocalDate> dates = new ArrayList<>();
        if (rangeLabel == null || !rangeLabel.contains("Du")) {
            return dates;
        }
        String normalized = rangeLabel.replace("Du", "").replace("Au", "|").trim();
        String[] parts = normalized.split("\\|");
        if (parts.length != 2) {
            return dates;
        }
        LocalDate start = LocalDate.parse(parts[0].trim(), RANGE_FORMAT);
        for (int i = 0; i < 7; i++) {
            dates.add(start.plusDays(i));
        }
        return dates;
    }

    private String buildConsultMobileUrl(SessionManager.SessionData session) {
        return buildAbsoluteUrl(
                session.sitePath,
                "consult-mobile.asp?QLangue=fr&pinOverride=" + session.nip + "&noEmployeOverride=" + session.userId
        );
    }

    private String buildAbsoluteUrl(String sitePath, String relativeUrl) {
        String base = sitePath.endsWith("/") ? sitePath : sitePath + "/";
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl;
        }
        return base + relativeUrl;
    }

    private String textOrEmpty(Element element) {
        return element != null ? element.text().trim() : "";
    }

    private static class NoteVariant {
        final boolean post;
        final String dateKey;
        final String dateValue;
        final String noteKey;

        NoteVariant(boolean post, String dateKey, String dateValue, String noteKey) {
            this.post = post;
            this.dateKey = dateKey;
            this.dateValue = dateValue;
            this.noteKey = noteKey;
        }
    }
}
