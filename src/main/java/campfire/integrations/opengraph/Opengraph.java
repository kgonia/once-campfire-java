package campfire.integrations.opengraph;

import campfire.integrations.net.Deadline;
import campfire.integrations.net.Network;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Link unfurling: `UnfurlLinksController#create` (reference/app/controllers/unfurl_links_controller.rb)
/// over `Opengraph::Metadata`, `Location`, `Fetch` and `Document` (reference/app/models/opengraph).
///
/// Every address is resolved through the private network guard and pinned, every redirect is
/// re-checked, and documents are capped at 5MB and 10 responses. As in the Rust port (Rails
/// gives each connect and read 60 seconds): an unfurl has 10 seconds in all and each connect or
/// read 5, at most 16 run at once (more wait their turn, within their deadline), and one that
/// runs out of time unfurls nothing. The endpoint is open to any signed-in user and fetches
/// pages they choose.
public final class Opengraph {
    private static final Logger LOG = Logger.getLogger(Opengraph.class.getName());

    private Opengraph() {}

    /// The most one unfurl may take, redirects and the image check included.
    public static final Duration UNFURL_DEADLINE = Duration.ofSeconds(10);

    /// Unfurls in flight at once.
    static final int MAX_CONCURRENT_UNFURLS = 16;
    private static final Semaphore SLOTS = new Semaphore(MAX_CONCURRENT_UNFURLS, true);
    private static final ExecutorService WORKERS = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("unfurl-", 0).factory());

    /// What `UnfurlLinksController#create` responds with.
    public sealed interface Unfurl {
        /// `render json: opengraph`: 200, `application/json; charset=utf-8`, with {@link #body}.
        record Json(Metadata metadata, String body) implements Unfurl {}

        /// `head :no_content` (204)
        record NoContent() implements Unfurl {}

        /// Where the Rails action raises: a 500.
        record Raised(String rubyClass) implements Unfurl {}
    }

    /// The action after `params.require(:url)` (a missing or blank `url` is the controller's
    /// 400), with the system network. Blocks for at most {@link #UNFURL_DEADLINE}.
    public static Unfurl unfurl(String url) {
        return unfurl(Network.system(), url, UNFURL_DEADLINE);
    }

    public static Unfurl unfurl(Network net, String url) {
        return unfurl(net, url, UNFURL_DEADLINE);
    }

    static Unfurl unfurl(Network net, String url, Duration limit) {
        Deadline deadline = Deadline.after(limit);
        Future<Unfurl> task = WORKERS.submit(() -> {
            if (!SLOTS.tryAcquire(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) return new Unfurl.NoContent();
            try {
                Metadata opengraph = Metadata.fromUrl(net, url, deadline);
                boolean valid = opengraph.validate();
                if (deadline.expired()) return new Unfurl.NoContent();
                return valid ? new Unfurl.Json(opengraph, opengraph.toJson()) : new Unfurl.NoContent();
            } catch (Metadata.RaisedException e) {
                return new Unfurl.Raised(e.rubyClass());
            } finally {
                SLOTS.release();
            }
        });
        try {
            // A little grace past the deadline: sockets close at it, but a DNS lookup can't be cut short.
            Unfurl result = task.get(deadline.remainingNanos() + TimeUnit.MILLISECONDS.toNanos(250), TimeUnit.NANOSECONDS);
            if (deadline.expired() && !(result instanceof Unfurl.Raised)) return giveUp(url, limit);
            return result;
        } catch (TimeoutException e) {
            task.cancel(true);
            return giveUp(url, limit);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return new Unfurl.NoContent();
        } catch (ExecutionException e) {
            LOG.log(Level.SEVERE, "Error unfurling " + url, e.getCause());
            return new Unfurl.Raised(e.getCause().getClass().getName());
        }
    }

    private static Unfurl giveUp(String url, Duration limit) {
        LOG.warning("Gave up unfurling " + url + " after " + limit);
        return new Unfurl.NoContent();
    }
}
