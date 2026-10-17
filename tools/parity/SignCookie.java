import campfire.rails.Secrets;
import java.sql.DriverManager;
import java.time.Instant;

/// Prints `session_token=<signed>` for the first session of the user with the given email, signed
/// with SECRET_KEY_BASE. Run with the app's jars on the classpath: `java -cp ... SignCookie.java DB EMAIL`.
public class SignCookie {
    public static void main(String[] args) throws Exception {
        String db = args[0], email = args[1];
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             var st = conn.prepareStatement("select s.token from sessions s join users u on u.id = s.user_id where u.email_address = ? limit 1")) {
            st.setString(1, email);
            var rs = st.executeQuery();
            if (!rs.next()) throw new IllegalStateException("no session for " + email);
            var secrets = new Secrets(System.getenv("SECRET_KEY_BASE"));
            Instant expires = Secrets.permanentExpiresAt(Instant.now());
            System.out.println("session_token=" + java.net.URLEncoder.encode(secrets.signCookie("session_token", rs.getString(1), expires), "UTF-8"));
        }
    }
}
