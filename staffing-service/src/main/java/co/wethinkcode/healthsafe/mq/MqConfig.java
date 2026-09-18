package co.wethinkcode.healthsafe.mq;

/**
 * Shared by every producer/consumer service that talks to the "staffing-events-topic"
 * ActiveMQ topic. Duplicated into each participating service's own source tree,
 * since these are independent Maven projects with no shared parent pom.
 */
public final class MqConfig {

    public static final String BROKER_URL = System.getenv().getOrDefault("ACTIVEMQ_BROKER_URL", "tcp://localhost:61616");
    public static final String TOPIC = System.getenv().getOrDefault("STAFFING_EVENTS_TOPIC", "staffing-events-topic");

    private MqConfig() {
    }
}
