package pl.piotrbuchman.dudugate;

import java.net.URI;
import org.json.JSONObject;

/** Routine-only credentials. Never stringify this object into diagnostics. */
final class RoborockCredentials {
    final String base, user, session, secret;
    final long routine;
    private final String json;
    RoborockCredentials(String value) throws Exception {
        if (value.length() > 8192) throw new IllegalArgumentException("size");
        JSONObject o = new JSONObject(value);
        if (o.getInt("schema_version") != 1) throw new IllegalArgumentException("schema");
        URI uri = new URI(o.getString("api_base_url"));
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || uri.getPort() != -1 || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || !("api-eu.roborock.com".equals(host) || "api-us.roborock.com".equals(host)
                || "api-cn.roborock.com".equals(host) || "api-ru.roborock.com".equals(host)))
            throw new IllegalArgumentException("endpoint");
        base = "https://" + host;
        Object identifier = o.get("routine_id");
        if (!(identifier instanceof Integer || identifier instanceof Long)) throw new IllegalArgumentException("routine type");
        routine = ((Number) identifier).longValue();
        if (routine <= 0) throw new IllegalArgumentException("routine");
        if (!"Full Cleaning".equals(o.getString("routine_name"))) throw new IllegalArgumentException("routine name");
        JSONObject auth = o.getJSONObject("auth");
        user = field(auth, "u"); session = field(auth, "s"); secret = field(auth, "h");
        json = new JSONObject().put("schema_version", 1).put("api_base_url", base)
                .put("routine_id", routine).put("routine_name", "Full Cleaning")
                .put("auth", new JSONObject().put("u", user).put("s", session).put("h", secret)).toString();
    }
    private static String field(JSONObject o, String key) throws Exception {
        String s = o.getString(key);
        if (!s.matches("[A-Za-z0-9_+=/.:-]{1,512}")) throw new IllegalArgumentException("credential");
        return s;
    }
    String serialized() { return json; }
}
