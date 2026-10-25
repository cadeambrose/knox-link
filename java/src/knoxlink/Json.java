package knoxlink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;

/**
 * Small JSON writer and reader. The writer understands Lua tables, so Lua can hand us its
 * state tables directly instead of building strings; the reader only needs to handle the
 * small command messages that browsers send.
 */
final class Json {
    private Json() {}

    // ---------------------------------------------------------------- writing

    static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (depth > 24) { sb.append("null"); return; }   // cycles in Lua tables
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            string(sb, (String) v);
        } else if (v instanceof Double || v instanceof Float) {
            number(sb, ((Number) v).doubleValue());
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof KahluaTable) {
            table(sb, (KahluaTable) v, depth);
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o, depth + 1);
            }
            sb.append(']');
        } else {
            string(sb, String.valueOf(v));
        }
    }

    /** A table whose keys are exactly 1..n becomes an array, anything else an object. */
    private static void table(StringBuilder sb, KahluaTable t, int depth) {
        int n = t.len();
        if (n > 0 && t.size() == n) {
            sb.append('[');
            for (int i = 1; i <= n; i++) {
                if (i > 1) sb.append(',');
                write(sb, t.rawget(i), depth + 1);
            }
            sb.append(']');
            return;
        }
        sb.append('{');
        boolean first = true;
        KahluaTableIterator it = t.iterator();
        while (it.advance()) {
            Object k = it.getKey();
            Object val = it.getValue();
            if (val == null) continue;
            if (!first) sb.append(',');
            first = false;
            String key;
            if (k instanceof Double && ((Double) k) == Math.rint((Double) k)) key = Long.toString(((Double) k).longValue());
            else key = String.valueOf(k);
            string(sb, key);
            sb.append(':');
            write(sb, val, depth + 1);
        }
        sb.append('}');
    }

    private static void number(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append('0'); return; }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) { sb.append((long) d); return; }
        // Three decimals are plenty for a status screen and keep messages small.
        double r = Math.round(d * 1000.0) / 1000.0;
        if (r == Math.rint(r)) sb.append((long) r);
        else sb.append(r);
    }

    static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- reading

    /** Parses a JSON document into Map / List / String / Double / Boolean / null. */
    static Object parse(String s) {
        Reader r = new Reader(s);
        Object v = r.value();
        r.ws();
        if (r.i != s.length()) throw new IllegalArgumentException("trailing data at " + r.i);
        return v;
    }

    private static final class Reader {
        final String s;
        int i;

        Reader(String s) { this.s = s; }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return num();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                expect(':');
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + (i - 1));
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + (i - 1));
            }
        }

        String str() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e);
                }
            }
        }

        Double num() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("bad value at " + i);
            return Double.valueOf(s.substring(start, i));
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i);
            i++;
        }
    }
}
