package com.pbuchman.duduhome.navigation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.StringReader;
import java.util.Set;

/** Private data only; never include values or parser exception text in diagnostics. */
public final class NavigationConfig {
    public static final int SLOT_COUNT = 3;
    public static final int MAX_BYTES = 16384;
    public record Destination(String label, String icon, String address, double latitude, double longitude,
                              String navigateBy) { }
    private final Destination[] slots;
    private NavigationConfig(Destination[] slots) { this.slots = slots.clone(); }
    public static NavigationConfig empty() { return new NavigationConfig(new Destination[SLOT_COUNT]); }
    public Destination destination(int index) { return slots[index]; }

    public static NavigationConfig parse(String text) throws JSONException {
        if (text == null || text.length() > MAX_BYTES) throw new JSONException("Invalid navigation document");
        Object value;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false); value = json(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new JSONException("Trailing navigation data");
        } catch (Exception ignored) { throw new JSONException("Invalid navigation document"); }
        if (!(value instanceof JSONObject root)) throw new JSONException("Invalid navigation document");
        keys(root, Set.of("schema_version", "slots"));
        if (integer(root.get("schema_version")) != 1) throw new JSONException("Unsupported navigation schema");
        JSONArray entries = root.getJSONArray("slots");
        if (entries.length() > SLOT_COUNT) throw new JSONException("Too many slots");
        Destination[] slots = new Destination[SLOT_COUNT];
        boolean[] seen = new boolean[SLOT_COUNT];
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            keys(entry, Set.of("slot", "destination"));
            int slot = integer(entry.get("slot")) - 1;
            if (slot < 0 || slot >= SLOT_COUNT || seen[slot] || !entry.has("destination"))
                throw new JSONException("Invalid navigation slot");
            seen[slot] = true;
            if (entry.isNull("destination")) continue;
            JSONObject d = entry.getJSONObject("destination");
            keys(d, Set.of("label", "icon", "address", "latitude", "longitude", "navigate_by"));
            String label = text(d.get("label"), 64);
            String icon = text(d.get("icon"), 12);
            if (!Set.of("home", "squash", "pin").contains(icon)) throw new JSONException("Invalid navigation icon");
            String address = d.has("address") ? text(d.get("address"), 160) : "";
            String navigateBy = d.has("navigate_by") ? text(d.get("navigate_by"), 11) : "coordinates";
            if (!Set.of("coordinates", "address").contains(navigateBy)
                    || (navigateBy.equals("address") && address.isEmpty()))
                throw new JSONException("Invalid navigation target mode");
            slots[slot] = new Destination(label, icon, address,
                    coordinate(d.get("latitude"), 90), coordinate(d.get("longitude"), 180), navigateBy);
        }
        return new NavigationConfig(slots);
    }
    private static Object json(JsonReader reader, int depth) throws Exception {
        if (depth > 8) throw new JSONException("Navigation document too deep");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                reader.beginObject(); JSONObject object = new JSONObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new JSONException("Duplicate navigation field");
                    object.put(key, json(reader, depth + 1));
                }
                reader.endObject(); return object;
            case BEGIN_ARRAY:
                reader.beginArray(); JSONArray array = new JSONArray();
                while (reader.hasNext()) array.put(json(reader, depth + 1));
                reader.endArray(); return array;
            case STRING: return reader.nextString();
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return JSONObject.NULL;
            case NUMBER:
                String number = reader.nextString();
                if (number.matches("-?[0-9]+")) {
                    long integer = Long.parseLong(number);
                    if (integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE) return (int) integer;
                    return integer;
                }
                double result = Double.parseDouble(number);
                if (!Double.isFinite(result)) throw new JSONException("Invalid navigation number");
                return result;
            default: throw new JSONException("Invalid navigation value");
        }
    }
    private static void keys(JSONObject object, Set<String> allowed) throws JSONException {
        var keys = object.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new JSONException("Unknown navigation field");
    }
    private static int integer(Object value) throws JSONException {
        if (!(value instanceof Integer)) throw new JSONException("Expected integer");
        return (Integer) value;
    }
    private static String text(Object value, int limit) throws JSONException {
        if (!(value instanceof String s) || s.trim().isEmpty() || s.length() > limit
                || !s.equals(s.trim()) || s.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT)) throw new JSONException("Invalid navigation text");
        return s;
    }
    private static double coordinate(Object value, double limit) throws JSONException {
        if (!(value instanceof Number)) throw new JSONException("Expected coordinate");
        double result = ((Number) value).doubleValue();
        if (!Double.isFinite(result) || Math.abs(result) > limit) throw new JSONException("Invalid coordinate");
        return result;
    }
    public String serialize() throws JSONException {
        JSONArray entries = new JSONArray();
        for (int i = 0; i < SLOT_COUNT; i++) {
            Destination d = slots[i];
            JSONObject entry = new JSONObject().put("slot", i + 1);
            if (d == null) entry.put("destination", JSONObject.NULL);
            else {
                JSONObject place = new JSONObject().put("label", d.label()).put("icon", d.icon())
                        .put("latitude", d.latitude()).put("longitude", d.longitude());
                if (!d.address().isEmpty()) place.put("address", d.address());
                if (!d.navigateBy().equals("coordinates")) place.put("navigate_by", d.navigateBy());
                entry.put("destination", place);
            }
            entries.put(entry);
        }
        return new JSONObject().put("schema_version", 1).put("slots", entries).toString();
    }
}
