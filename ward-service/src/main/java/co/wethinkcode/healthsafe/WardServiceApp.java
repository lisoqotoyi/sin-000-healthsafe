package co.wethinkcode.healthsafe;

import co.wethinkcode.healthsafe.mq.MqConfig;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WardServiceApp {

    private static final Logger LOGGER = Logger.getLogger(WardServiceApp.class.getName());
    private static final String INGESTION_URL = System.getenv().getOrDefault(
            "INGESTION_SERVICE_URL", "http://localhost:7030");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7031);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/wards", ctx -> {
            try {
                ctx.json(fetchWards());
            } catch (DependencyException exception) {
                ctx.status(503).json(Map.of("error", exception.getMessage()));
            }
        });
        app.get("/wards/{id}", ctx -> {
            try {
                List<Map<String, Object>> wards = fetchWards();
                Map<String, Object> ward = matchWard(wards, ctx.pathParam("id"));
                if (ward == null) {
                    ctx.status(404).json(Map.of("error", "Ward not found"));
                } else {
                    ctx.json(ward);
                }
            } catch (DependencyException exception) {
                ctx.status(503).json(Map.of("error", exception.getMessage()));
            }
        });
        app.post("/wards/{id}/equipment-failures", ctx -> {
            try {
                Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(ctx.body(), new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
                String equipment = String.valueOf(body.getOrDefault("equipment", "unknown equipment"));
                String failure = String.valueOf(body.getOrDefault("failure", "Equipment unavailable"));
                publishEquipmentFailure(ctx.pathParam("id"), equipment, failure);
                ctx.status(202).json(Map.of("status", "queued"));
            } catch (Exception exception) {
                ctx.status(400).json(Map.of("error", "Invalid equipment failure request"));
            }
        });
        startStaffingConsumer();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> fetchWards() throws DependencyException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(INGESTION_URL + "/wards"))
                .timeout(Duration.ofSeconds(3)).GET().build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new DependencyException("Ingestion service returned HTTP " + response.statusCode());
            }
            return Arrays.asList(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(response.body(), Map[].class));
        } catch (Exception exception) {
            if (exception instanceof DependencyException dependencyException) {
                throw dependencyException;
            }
            throw new DependencyException("Ingestion service unavailable");
        }
    }

    static Map<String, Object> matchWard(List<Map<String, Object>> wards, String requestedId) {
        String normalizedRequest = normalizeWardId(requestedId);
        for (Map<String, Object> candidate : wards) {
            String candidateId = String.valueOf(candidate.getOrDefault("id", ""));
            if (normalizeWardId(candidateId).equals(normalizedRequest)) {
                return candidate;
            }
        }
        return null;
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

    static class DependencyException extends Exception {
        DependencyException(String message) {
            super(message);
        }
    }

    private static void startStaffingConsumer() {
        Thread consumerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);
                    try (Connection connection = factory.createConnection()) {
                        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                        MessageConsumer consumer = session.createConsumer(session.createTopic(MqConfig.TOPIC));
                        consumer.setMessageListener(message -> {
                            try {
                                if (message instanceof TextMessage textMessage) {
                                    LOGGER.info("Received staffing update: " + textMessage.getText());
                                }
                            } catch (Exception exception) {
                                LOGGER.log(Level.WARNING, "Malformed staffing update", exception);
                            }
                        });
                        connection.start();
                        while (!Thread.currentThread().isInterrupted()) {
                            Thread.sleep(1000);
                        }
                        consumer.close();
                        session.close();
                    }
                } catch (Exception exception) {
                    LOGGER.log(Level.WARNING, "Staffing topic unavailable; retrying", exception);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "staffing-event-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    private static void publishEquipmentFailure(String wardId, String equipment, String failure) throws Exception {
        Map<String, String> event = Map.of("eventId", "equipment-" + UUID.randomUUID(), "wardId", wardId,
                "equipment", equipment, "failure", failure, "timestamp", Instant.now().toString());
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);
        try (Connection connection = factory.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(MqConfig.QUEUE));
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            producer.send(session.createTextMessage(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event)));
            producer.close();
            session.close();
        }
    }
}
