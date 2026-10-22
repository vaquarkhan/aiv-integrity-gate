/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonLiteTest {

    @Test
    void parsesEveryValueKind() {
        Object v = JsonLite.parse(" {\"a\":[1, 2.5, true, false, null, \"s\\n\\t\\\"\\\\\\/\\b\\f\\r\\u0041\"], \"b\":{}} ");
        List<?> a = (List<?>) JsonLite.get(v, "a");
        assertEquals(1L, a.get(0));
        assertEquals(2.5, a.get(1));
        assertEquals(Boolean.TRUE, a.get(2));
        assertEquals(Boolean.FALSE, a.get(3));
        assertNull(a.get(4));
        assertEquals("s\n\t\"\\/\b\f\rA", a.get(5));
        assertNull(JsonLite.get(v, "a", "x"));
        assertNull(JsonLite.get(v, "zzz"));
        assertTrue(JsonLite.list("nope").isEmpty());
        assertEquals(2, JsonLite.list(List.of(1, 2)).size());
        assertNull(JsonLite.str(null));
        assertEquals("7", JsonLite.str(7));
        assertEquals(List.of(), JsonLite.parse("[]"));
        assertEquals(Map.of(), JsonLite.parse("{}"));
        assertEquals(-3L, JsonLite.parse("-3"));
        assertEquals(1e3, JsonLite.parse("1E3"));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : List.of("", "{", "[1,", "{\"a\" 1}", "{1:2}", "{\"a\":1,}", "{\"a\":1 \"b\":2}", "[1 2]", "\"abc",
                "\"\\x\"", "\"\\u12\"", "tru", "nul", "-", "1.2.3", "{} x", "@", "[")) {
            assertThrows(IllegalArgumentException.class, () -> JsonLite.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> JsonLite.parse(null));
    }

    @Test
    void acceptsRfc8259NumberGrammar() {
        for (String valid : List.of("0", "-0", "10", "-10", "0.1", "-0.1", "1e2", "1E+2", "1e-2")) {
            JsonLite.parse(valid);
        }
    }

    @Test
    void rejectsNonRfcStringsNumbersAndWhitespace() {
        for (String bad : List.of("+1", "01", "-01", ".1", "1.", "1e", "1e+", "1e-", "-", "١",
                "999999999999999999999999999999999999999",
                "\"raw\u0000control\"", "\"raw\nnewline\"", "\u00a0{}", "{}\u000b")) {
            assertThrows(IllegalArgumentException.class, () -> JsonLite.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> JsonLite.parse("\"\\uZZZZ\""));
    }

    @Test
    void writesAndRoundTrips() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "q\"\\\n\r\t\u0001x");
        m.put("n", 3);
        m.put("d", 2.5);
        m.put("b", true);
        m.put("z", null);
        m.put("l", List.of(1, "two", List.of()));
        m.put("o", Map.of("k", "v"));
        m.put("other", new StringBuilder("sb"));
        String json = JsonLite.write(m);
        assertEquals("{\"s\":\"q\\\"\\\\\\n\\r\\t\\u0001x\",\"n\":3,\"d\":2.5,\"b\":true,\"z\":null,"
                + "\"l\":[1,\"two\",[]],\"o\":{\"k\":\"v\"},\"other\":\"sb\"}", json);
        Object back = JsonLite.parse(json);
        assertEquals("q\"\\\n\r\t\u0001x", JsonLite.get(back, "s"));
        assertEquals("v", JsonLite.get(back, "o", "k"));
        assertEquals("null", JsonLite.write(null));
    }
}
