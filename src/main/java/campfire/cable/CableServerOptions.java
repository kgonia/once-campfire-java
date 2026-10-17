package campfire.cable;

import io.quarkus.arc.Unremovable;
import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.HttpServerOptions;
import jakarta.inject.Singleton;

/// Offers Action Cable's subprotocols on Quarkus' HTTP servers. Without them Vert.x completes the
/// handshake with no `Sec-WebSocket-Protocol`, and browsers (which asked for
/// `actioncable-v1-json`) fail the connection.
@Singleton
@Unremovable
public class CableServerOptions implements HttpServerOptionsCustomizer {
    @Override
    public void customizeHttpServer(HttpServerOptions options) {
        Cable.configure(options);
    }

    @Override
    public void customizeHttpsServer(HttpServerOptions options) {
        Cable.configure(options);
    }
}
