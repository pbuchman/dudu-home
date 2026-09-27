package com.pbuchman.duduhome.navigation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.StringReader;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;

/** Private data only; never include values or parser exception text in diagnostics. */
public final class NavigationConfig {
    public static final int SLOT_COUNT = 3;
    public static final int MAX_BYTES = 16384;
    public record Destination(String label, String icon, String address, double latitude, double longitude,
                              String navigateBy) { }
    public static final int MAX_DESTINATIONS = 12;
    public record Slot(String label, String icon, List<Destination> destinations) {
        public Slot { destinations = List.copyOf(destinations); }
    }
    private final Slot[] slots;
    private final int schema;
    private NavigationConfig(Slot[] slots, int schema) { this.slots = slots.clone(); this.schema = schema; }
    public static NavigationConfig empty() { return new NavigationConfig(new Slot[SLOT_COUNT], 1); }
    public Slot slot(int index) { return slots[index]; }
    /** Compatibility accessor for single-destination callers. Never silently select a group. */
    public Destination destination(int index) {
        Slot slot = slots[index];
        return slot != null && slot.destinations().size() == 1 ? slot.destinations().get(0) : null;
    }

    public static NavigationConfig parse(String text) throws JSONException {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new JSONException("Invalid navigation document");
        Object value;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false); value = json(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new JSONException("Trailing navigation data");
        } catch (Exception ignored) { throw new JSONException("Invalid navigation document"); }
        if (!(value instanceof JSONObject root)) throw new JSONException("Invalid navigation document");
        keys(root, Set.of("schema_version", "slots"));
        int schema = integer(root.get("schema_version"));
        if (schema != 1 && schema != 2) throw new JSONException("Unsupported navigation schema");
        JSONArray entries = root.getJSONArray("slots");
        if (entries.length() > SLOT_COUNT) throw new JSONException("Too many slots");
        Slot[] slots = new Slot[SLOT_COUNT];
        boolean[] seen = new boolean[SLOT_COUNT];
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            keys(entry, schema == 1 ? Set.of("slot", "destination")
                    : Set.of("slot", "label", "icon", "destinations"));
            int slot = integer(entry.get("slot")) - 1;
            if (slot < 0 || slot >= SLOT_COUNT || seen[slot]) throw new JSONException("Invalid navigation slot");
            seen[slot] = true;
            if (schema == 1) {
                if (!entry.has("destination")) throw new JSONException("Missing destination");
                if (entry.isNull("destination")) continue;
                Destination d = parseDestination(entry.getJSONObject("destination"));
                slots[slot] = new Slot(d.label(), d.icon(), List.of(d));
            } else {
                JSONArray places = entry.getJSONArray("destinations");
                if (places.length() > MAX_DESTINATIONS) throw new JSONException("Too many destinations");
                // Validate even optional metadata on an empty slot; do not hide invalid fields.
                String label = entry.has("label") ? text(entry.get("label"), 64) : "";
                String icon = entry.has("icon") ? icon(entry.get("icon")) : "";
                if (places.length() == 0) continue;
                if (label.isEmpty() || icon.isEmpty()) throw new JSONException("Missing slot metadata");
                List<Destination> destinations = new ArrayList<>();
                for (int n = 0; n < places.length(); n++) destinations.add(parseDestination(places.getJSONObject(n)));
                slots[slot] = new Slot(label, icon, destinations);
            }
        }
        return new NavigationConfig(slots, schema);
    }
    private static String icon(Object value) throws JSONException {
        String icon = text(value, 12);
        if (!Set.of("home", "squash", "pin").contains(icon)) throw new JSONException("Invalid navigation icon");
        return icon;
    }
    private static Destination parseDestination(JSONObject d) throws JSONException {
        keys(d, Set.of("label", "icon", "address", "latitude", "longitude", "navigate_by"));
        String label = text(d.get("label"), 64);
        String icon = icon(d.get("icon"));
        String address = d.has("address") ? text(d.get("address"), 160) : "";
        String navigateBy = d.has("navigate_by") ? text(d.get("navigate_by"), 11) : "coordinates";
        if (!Set.of("coordinates", "address").contains(navigateBy)
                || (navigateBy.equals("address") && address.isEmpty()))
            throw new JSONException("Invalid navigation target mode");
        return new Destination(label, icon, address,
                coordinate(d.get("latitude"), 90), coordinate(d.get("longitude"), 180), navigateBy);
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
    private static JSONObject serializeDestination(Destination d) throws JSONException {
        JSONObject place = new JSONObject().put("label", d.label()).put("icon", d.icon())
                .put("latitude", d.latitude()).put("longitude", d.longitude());
        if (!d.address().isEmpty()) place.put("address", d.address());
        if (!d.navigateBy().equals("coordinates")) place.put("navigate_by", d.navigateBy());
        return place;
    }
    public String serialize() throws JSONException {
        JSONArray entries = new JSONArray();
        for (int i = 0; i < SLOT_COUNT; i++) {
            Slot slot = slots[i];
            JSONObject entry = new JSONObject().put("slot", i + 1);
            if (schema == 1) entry.put("destination", slot == null ? JSONObject.NULL
                    : serializeDestination(slot.destinations().get(0)));
            else {
                JSONArray places = new JSONArray();
                if (slot != null) {
                    entry.put("label", slot.label()).put("icon", slot.icon());
                    for (Destination d : slot.destinations()) places.put(serializeDestination(d));
                }
                entry.put("destinations", places);
            }
            entries.put(entry);
        }
        String result = new JSONObject().put("schema_version", schema).put("slots", entries).toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new JSONException("Navigation document too large");
        return result;
    }
}
