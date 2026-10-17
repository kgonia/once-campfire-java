package campfire.useragent;

/// Where Ruby raises (a `NoMethodError` on nil, say) instead of answering. The `try` methods
/// throw it; the public ones answer `""` or `false` in its place.
final class Raised extends RuntimeException {
    static final Raised INSTANCE = new Raised();

    private Raised() {
        super("raised in Ruby", null, false, false);
    }
}
