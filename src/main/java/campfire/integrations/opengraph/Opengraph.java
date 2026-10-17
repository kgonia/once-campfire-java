package campfire.integrations.opengraph;

import campfire.integrations.net.Network;
import io.quarkus.logging.Log;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Link unfurling: `UnfurlLinksController#create` (reference/app/controllers/unfurl_links_controller.rb)
/// over `Opengraph::Metadata`, `Location`, `Fetch` and `Document` (reference/app/models/opengraph).
///
/// Every address is resolved through the private network guard and pinned, every redirect is
/// re-checked, and documents are capped at 5MB and 10 responses. As in the Rust port (Rails
/// gives each connect and read 60 seconds): an unfurl has 10 seconds in all and each connect or
/// read 5, at most 16 run at once (more wait their turn, within their deadline), and one that
/// runs out of time unfurls nothing. The endpoint is open to any signed-in user and fetches
/// pages they choose.
public final class Opengraph implements AutoCloseable {

    /// The most one unfurl may take, redirects and the image check included.
    public static final Duration UNFURL_DEADLINE = Duration.ofSeconds(10);

    /// Unfurls in flight at once.
    static final int MAX_CONCURRENT_UNFURLS = 16;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_UNFURLS, true);
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("unfurl-", 0).factory());
    private final Network net;

    public Opengraph(Network net) {
        this.net = net;
    }

    /// What `UnfurlLinksController#create` responds with.
    public sealed interface Unfurl {
        /// `render json: opengraph`: 200, `application/json; charset=utf-8`, with {@link #body}.
        record Json(String body) implements Unfurl {}

        /// `head :no_content` (204)
        record NoContent() implements Unfurl {}
    }

    /// The action after `params.require(:url)` (a missing or blank `url` is the controller's
    /// 400). Blocks for at most {@link #UNFURL_DEADLINE}.
    public Unfurl unfurl(String url) {
        return unfurl(url, UNFURL_DEADLINE);
    }

    /// The worker is interrupted at the deadline, which ends the fetch it's waiting on. A bug in
    /// the unfurl is thrown, for a 500.
    Unfurl unfurl(String url, Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        Future<Unfurl> task = workers.submit(() -> {
            if (!slots.tryAcquire(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) return new Unfurl.NoContent();
            try {
                Metadata opengraph = Metadata.fromUrl(net, url);
                boolean valid = opengraph.validate();
                if (expired(deadline)) return new Unfurl.NoContent();
                return valid ? new Unfurl.Json(opengraph.toJson()) : new Unfurl.NoContent();
            } finally {
                slots.release();
            }
        });
        try {
            // A little grace past the deadline, which a DNS lookup can't be cut short at.
            Unfurl result = task.get(deadline - System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250), TimeUnit.NANOSECONDS);
            if (expired(deadline)) return giveUp(url, limit);
            return result;
        } catch (TimeoutException e) {
            task.cancel(true);
            return giveUp(url, limit);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return new Unfurl.NoContent();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Error unfurling " + url, e.getCause());
        }
    }

    /// Waits for unfurls in flight, which their deadline bounds.
    @Override
    public void close() {
        workers.close();
    }

    private static boolean expired(long deadline) {
        return deadline - System.nanoTime() <= 0;
    }

    private static Unfurl giveUp(String url, Duration limit) {
        Log.warn("Gave up unfurling " + url + " after " + limit);
        return new Unfurl.NoContent();
    }
}
