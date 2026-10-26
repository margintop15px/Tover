import java.net.HttpURLConnection;
import java.net.URI;

public final class Healthcheck {
    public static void main(String[] args) throws Exception {
        var port = System.getenv().getOrDefault("TOVER_PORT", "3030");
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/readyz").toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        try {
            if (connection.getResponseCode() != 200) System.exit(1);
        } finally {
            connection.disconnect();
        }
    }
}
