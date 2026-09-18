package co.wethinkcode.healthsafe;

import co.wethinkcode.healthsafe.mq.MqConfig;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

public class StaffingServiceApp {

    private static final String WARD_URL = System.getenv().getOrDefault("WARD_SERVICE_URL", "http://localhost:7031");
    private static final String ALERT_URL = System.getenv().getOrDefault("ALERT_LEVEL_SERVICE_URL", "http://localhost:7032");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7033);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/staffing/{wardId}", ctx -> schedule(ctx.pathParam("wardId"), ctx));
        app.get("/schedule/{wardId}", ctx -> schedule(ctx.pathParam("wardId"), ctx));
        app.post("/staffing/{wardId}", ctx -> {
            try {
                Map<String, Object> schedule = buildSchedule(ctx.pathParam("wardId"));
                publishStaffingEvent(ctx.pathParam("wardId"), schedule);
                ctx.status(202).json(schedule);
            } catch (DependencyException exception) {
                ctx.status(exception.status).json(Map.of("error", exception.getMessage()));
            } catch (Exception exception) {
                ctx.status(503).json(Map.of("error", "Could not publish staffing event"));
            }
        });
    }

    private static void schedule(String wardId, io.javalin.http.Context ctx) {
        try {
            ctx.json(buildSchedule(wardId));
        } catch (DependencyException exception) {
            ctx.status(exception.status).json(Map.of("error", exception.getMessage()));
        }
    }

    private static Map<String, Object> buildSchedule(String wardId) throws DependencyException {
        String safeWardId = normalizeWardId(wardId);
        Map<String, Object> ward = getJson(WARD_URL + "/wards/" + safeWardId);
        int level = ((Number) getJson(ALERT_URL + "/alert-level").get("level")).intValue();
        int doctorCount = Math.max(1, 1 + level / 2);
        List<String> doctors = new ArrayList<>();
        for (int index = 1; index <= doctorCount; index++) {
            doctors.add("on-call-doctor-" + index);
        }
        return Map.of("ward", ward, "alertLevel", level, "doctors", doctors);
    }

    private static void publishStaffingEvent(String wardId, Map<String, Object> schedule) throws Exception {
        Map<String, Object> event = Map.of("eventId", UUID.randomUUID().toString(), "wardId", normalizeWardId(wardId),
                "status", "UPDATED", "timestamp", Instant.now().toString(), "schedule", schedule);
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);
        try (Connection connection = factory.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createTopic(MqConfig.TOPIC));
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            TextMessage message = session.createTextMessage(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event));
            producer.send(message);
            producer.close();
            session.close();
        }
    }

    static String normalizeWardId(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            return "";
        }
        if (cleaned.startsWith("W-")) {
            cleaned = cleaned.substring(2);
        } else if (cleaned.startsWith("W")) {
            cleaned = cleaned.substring(1);
        }
        cleaned = cleaned.replaceFirst("^0+(?=\\d)", "");
        if (cleaned.isEmpty()) {
            return "W-0";
        }
        return "W-" + cleaned;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getJson(String url) throws DependencyException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new DependencyException(404, "Ward not found");
            }
            if (response.statusCode() != 200) {
                throw new DependencyException(503, "Dependency returned HTTP " + response.statusCode());
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), Map.class);
        } catch (DependencyException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DependencyException(503, "Required service unavailable");
        }
    }

    static class DependencyException extends Exception {
        private final int status;

        DependencyException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
