package co.wethinkcode.healthsafe;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import io.javalin.Javalin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public class IngestionServiceApp {

    private static final Logger LOGGER = Logger.getLogger(IngestionServiceApp.class.getName());
    private static final String RESOURCE = "/wards-outdated.csv";

    public static void main(String[] args) {
        List<WardRecord> wards = loadWards();
        Javalin app = Javalin.create().start(7030);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/wards", ctx -> ctx.json(wards));
    }

    static List<WardRecord> loadWards() {
        Map<String, WardRecord> wardsById = new LinkedHashMap<>();
        try (InputStream input = IngestionServiceApp.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            try (CSVReader reader = new CSVReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String[] row;
                int rowNumber = 1;
                reader.readNext();
                while ((row = reader.readNext()) != null) {
                    rowNumber++;
                    WardRecord record = parseRow(row, rowNumber);
                    if (record == null) {
                        continue;
                    }
                    wardsById.merge(record.id(), record, IngestionServiceApp::mergeRecords);
                }
            }
        } catch (IOException | CsvValidationException exception) {
            throw new IllegalStateException("Could not read " + RESOURCE, exception);
        }
        return new ArrayList<>(wardsById.values());
    }

    private static WardRecord parseRow(String[] row, int rowNumber) {
        if (row.length != 4) {
            LOGGER.warning("Skipping malformed CSV row " + rowNumber + ": expected 4 columns");
            return null;
        }
        String id = normalizeId(row[0]);
        if (id == null) {
            LOGGER.warning("Skipping CSV row " + rowNumber + ": missing ward ID");
            return null;
        }
        String wing = normalizeName(row[1]);
        String department = normalizeDepartment(row[2]);
        String rawBeds = clean(row[3]);
        Integer beds = parseBeds(rawBeds);
        String notes = beds == null && !isMissing(rawBeds)
                ? "bedsAvailable was non-numeric or invalid ('" + rawBeds + "')"
                : null;
        return new WardRecord(id, wing, department, beds, notes);
    }

    private static WardRecord mergeRecords(WardRecord first, WardRecord duplicate) {
        boolean useFirstBeds = first.bedsAvailable() != null;
        Integer beds = useFirstBeds ? first.bedsAvailable() : duplicate.bedsAvailable();
        String notes = useFirstBeds ? first.notes() : duplicate.notes();
        if (beds == null) {
            notes = first.notes() != null ? first.notes() : duplicate.notes();
        }
        return new WardRecord(first.id(), prefer(first.wing(), duplicate.wing()),
                prefer(first.department(), duplicate.department()),
                beds, notes);
    }

    private static String prefer(String first, String second) {
        return isMissing(first) ? second : first;
    }

    private static String normalizeId(String value) {
        String cleaned = clean(value).toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (isMissing(cleaned)) {
            return null;
        }
        return cleaned.matches("W-?[0-9]+") ? cleaned.replaceFirst("W(?=[0-9])", "W-") : null;
    }

    private static String normalizeName(String value) {
        if (isMissing(value)) {
            return null;
        }
        String[] words = clean(value).toLowerCase(Locale.ROOT).split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static String normalizeDepartment(String value) {
        String department = normalizeName(value);
        if ("Pediatrics".equals(department)) {
            return "Paediatrics";
        }
        return "Icu".equals(department) ? "ICU" : department;
    }

    private static Integer parseBeds(String value) {
        if (isMissing(value)) {
            return null;
        }
        try {
            int beds = Integer.parseInt(value);
            return beds >= 0 && beds <= 1000 ? beds : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static boolean isMissing(String value) {
        return value == null || value.isBlank() || switch (value.toLowerCase(Locale.ROOT)) {
            case "n/a", "na", "tbd", "unknown", "-", "nan" -> true;
            default -> false;
        };
    }

    public record WardRecord(String id, String wing, String department, Integer bedsAvailable, String notes) {
    }
}
