package pl.piotrbuchman.dudugate;

import java.net.URI;
import org.json.JSONObject;

/** Routine-only credentials. Never stringify this object into diagnostics. */
final class RoborockCredentials {
    final String base, user, session, secret;
    final long routine;
    final long fullMopRoutine;
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
        fullMopRoutine = o.has("full_mop_routine_id") ? positiveId(o.get("full_mop_routine_id")) : 0;
        if (fullMopRoutine == routine) throw new IllegalArgumentException("distinct routines required");
        JSONObject auth = o.getJSONObject("auth");
        user = field(auth, "u"); session = field(auth, "s"); secret = field(auth, "h");
        JSONObject canonical = new JSONObject().put("schema_version", 1).put("api_base_url", base)
                .put("routine_id", routine).put("routine_name", "Full Cleaning")
                .put("auth", new JSONObject().put("u", user).put("s", session).put("h", secret));
        if (fullMopRoutine != 0) canonical.put("full_mop_routine_id", fullMopRoutine);
        json = canonical.toString();
    }
    private RoborockCredentials(RoborockCredentials source, long selected) {
        base = source.base; user = source.user; session = source.session; secret = source.secret;
        routine = selected; fullMopRoutine = source.fullMopRoutine; json = source.json;
    }
    /** Explicit selection only. Missing Mop never falls back to Full Cleaning. */
    RoborockCredentials forAction(HomeAction action) {
        if (action == HomeAction.CLEANING) return this;
        if (action == HomeAction.MOP && fullMopRoutine > 0) return new RoborockCredentials(this, fullMopRoutine);
        throw new IllegalArgumentException("routine unavailable");
    }
    private static long positiveId(Object value) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() <= 0)
            throw new IllegalArgumentException("routine type");
        return ((Number) value).longValue();
    }
    private static String field(JSONObject o, String key) throws Exception {
        String s = o.getString(key);
        if (!s.matches("[A-Za-z0-9_+=/.:-]{1,512}")) throw new IllegalArgumentException("credential");
        return s;
    }
    String serialized() { return json; }
}
