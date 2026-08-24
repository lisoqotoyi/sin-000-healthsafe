package co.wethinkcode.healthsafe;

import io.javalin.Javalin;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AlertLevelServiceApp {

    private static final AtomicInteger currentLevel = new AtomicInteger(0);

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7032);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/alert-level", ctx -> ctx.json(Map.of("level", currentLevel.get())));
        app.put("/alert-level", ctx -> updateLevel(ctx));
        app.post("/alert-level", ctx -> updateLevel(ctx));
    }

    private static void updateLevel(io.javalin.http.Context ctx) {
        try {
            LevelRequest request = ctx.bodyAsClass(LevelRequest.class);
            if (request == null || request.level() < 0 || request.level() > 8) {
                ctx.status(400).json(Map.of("error", "Alert level must be between 0 and 8"));
                return;
            }
            currentLevel.set(request.level());
            ctx.json(Map.of("level", currentLevel.get()));
        } catch (Exception exception) {
            ctx.status(400).json(Map.of("error", "Request must contain a numeric level"));
        }
    }

    public record LevelRequest(int level) {
    }
}
