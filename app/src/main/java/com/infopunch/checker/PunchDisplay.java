package com.infopunch.checker;

import java.util.ArrayList;
import java.util.List;

public class PunchDisplay {
    public static class Pair {
        public final String entry;
        public final String exit;

        public Pair(String entry, String exit) {
            this.entry = normalize(entry);
            this.exit = normalize(exit);
        }
    }

    public static List<Pair> parsePairs(List<String> shifts) {
        List<Pair> pairs = new ArrayList<>();
        if (shifts == null) {
            return pairs;
        }
        for (String shift : shifts) {
            String[] parts = shift == null ? new String[0] : shift.split("-");
            String entry = parts.length > 0 ? parts[0] : "";
            String exit = parts.length > 1 ? parts[1] : "";
            pairs.add(new Pair(entry, exit));
        }
        return pairs;
    }

    public static String buildTwoColumnText(List<String> shifts) {
        List<Pair> pairs = parsePairs(shifts);
        if (pairs.isEmpty()) {
            return "Aucun poincon visible pour cette journee.";
        }
        StringBuilder builder = new StringBuilder("Entree     Sortie");
        for (Pair pair : pairs) {
            builder.append("\n")
                    .append(padRight(pair.entry, 10))
                    .append(pair.exit);
        }
        return builder.toString();
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? "--:--" : normalized;
    }

    private static String padRight(String value, int width) {
        String normalized = normalize(value);
        StringBuilder builder = new StringBuilder(normalized);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }
}
