package com.pbuchman.duduhome.routebook;

import java.util.*;

/** Strict, bounded JSON reader: no coercion, duplicate keys or permissive Android JSON syntax. */
public final class Json {
    private final String text; private int pos;
    private Json(String text) { this.text = text; }
    public static Object parse(String text) {
        if (text.length() > 262144) throw new IllegalArgumentException();
        Json r = new Json(text); Object value = r.value(0); r.space();
        if (r.pos != text.length()) throw new IllegalArgumentException();
        return value;
    }
    private void space() { while (pos < text.length() && " \r\n\t".indexOf(text.charAt(pos)) >= 0) pos++; }
    private char take() { if (pos >= text.length()) throw new IllegalArgumentException(); return text.charAt(pos++); }
    private Object value(int depth) {
        if (depth > 16) throw new IllegalArgumentException(); space(); char c = take();
        if (c == '{') {
            Map<String,Object> m = new LinkedHashMap<>(); space();
            if (pos < text.length() && text.charAt(pos) == '}') { pos++; return m; }
            do { space(); if (take() != '"') throw new IllegalArgumentException(); String key = string();
                space(); if (take() != ':' || m.containsKey(key)) throw new IllegalArgumentException();
                m.put(key, value(depth+1)); space(); c = take(); if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException();
            } while (true);
        }
        if (c == '[') {
            List<Object> a = new ArrayList<>(); space();
            if (pos < text.length() && text.charAt(pos) == ']') { pos++; return a; }
            do { a.add(value(depth+1)); space(); c=take(); if(c==']') return a;
                if(c!=',') throw new IllegalArgumentException(); } while(true);
        }
        if (c == '"') return string();
        for (String literal : new String[]{"true","false","null"}) {
            if (c == literal.charAt(0) && text.startsWith(literal.substring(1),pos)) {
                pos += literal.length()-1;
                return literal.equals("null") ? null : literal.equals("true");
            }
        }
        int start=pos-1;
        while(pos<text.length() && "0123456789.eE+-".indexOf(text.charAt(pos))>=0) pos++;
        String n=text.substring(start,pos);
        if(!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IllegalArgumentException();
        if(n.indexOf('.')<0 && n.indexOf('e')<0 && n.indexOf('E')<0) return Long.parseLong(n);
        double d=Double.parseDouble(n); if(!Double.isFinite(d)) throw new IllegalArgumentException(); return d;
    }
    private String string() {
        StringBuilder s=new StringBuilder();
        while(true) { char c=take(); if(c=='"') break;
            if(c<32) throw new IllegalArgumentException();
            if(c=='\\') { c=take(); switch(c) {
                case '"': case '\\': case '/': break;
                case 'b': c='\b'; break; case 'f': c='\f'; break;
                case 'n': c='\n'; break; case 'r': c='\r'; break; case 't': c='\t'; break;
                case 'u': if(pos+4>text.length()) throw new IllegalArgumentException();
                    c=(char)Integer.parseInt(text.substring(pos,pos+4),16); pos+=4; break;
                default: throw new IllegalArgumentException(); }
            } s.append(c);
        }
        String result=s.toString();
        for(int i=0;i<result.length();i++) { char c=result.charAt(i);
            if(Character.isHighSurrogate(c)) { if(++i>=result.length() || !Character.isLowSurrogate(result.charAt(i))) throw new IllegalArgumentException(); }
            else if(Character.isLowSurrogate(c)) throw new IllegalArgumentException(); }
        return result;
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object v, String... keys) {
        if(!(v instanceof Map)) throw new IllegalArgumentException(); Map<String,Object> m=(Map<String,Object>)v;
        if(!m.keySet().equals(new HashSet<>(Arrays.asList(keys)))) throw new IllegalArgumentException(); return m;
    }
    public static long integer(Object v) { if(!(v instanceof Long)) throw new IllegalArgumentException(); return (Long)v; }
    public static String string(Object v) { if(!(v instanceof String)) throw new IllegalArgumentException(); return (String)v; }
    public static String quote(String s) {
        StringBuilder b=new StringBuilder("\"");
        for(char c:s.toCharArray()) { if(c=='"'||c=='\\') b.append('\\');
            if(c<32) b.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else b.append(c); }
        return b.append('"').toString();
    }
}
