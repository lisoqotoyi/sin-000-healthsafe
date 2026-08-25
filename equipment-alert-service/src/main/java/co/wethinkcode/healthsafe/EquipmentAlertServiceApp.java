package co.wethinkcode.healthsafe;

import co.wethinkcode.healthsafe.mq.MqConfig;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EquipmentAlertServiceApp {

    private static final Logger LOGGER = Logger.getLogger(EquipmentAlertServiceApp.class.getName());

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7034);

        app.get("/health", ctx -> ctx.result("OK"));
        startConsumer();
    }

    private static void startConsumer() {
        Thread consumerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    consumeUntilDisconnected();
                } catch (Exception exception) {
                    LOGGER.log(Level.WARNING, "Equipment alert broker unavailable; retrying", exception);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "equipment-alert-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    private static void consumeUntilDisconnected() throws Exception {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);
        try (Connection connection = factory.createConnection()) {
            Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createQueue(MqConfig.QUEUE));
            consumer.setMessageListener(message -> process(message));
            connection.start();
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(1000);
            }
            consumer.close();
            session.close();
        }
    }

    private static void process(Message message) {
        try {
            if (!(message instanceof TextMessage textMessage)) {
                LOGGER.warning("Ignoring non-text equipment alert message");
                message.acknowledge();
                return;
            }
            Map<?, ?> alert = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(textMessage.getText(), Map.class);
            LOGGER.info("Received equipment failure alert: " + alert);
            message.acknowledge();
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Malformed equipment alert; acknowledging poison message", exception);
            try {
                message.acknowledge();
            } catch (Exception acknowledgeFailure) {
                LOGGER.log(Level.WARNING, "Could not acknowledge equipment alert", acknowledgeFailure);
            }
        }
    }
}
