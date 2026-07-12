package com.wildmare.market.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextUtilTest {
    @Test
    void convertsLegacyHtmlAngleEntitiesToMiniMessageEscapes() {
        assertEquals("\\<symbol>", TextUtil.normalizeMiniMessage("&lt;symbol&gt;"));
    }

    @Test
    void preservesModernMiniMessageEscaping() {
        assertEquals("\\<symbol>", TextUtil.normalizeMiniMessage("\\<symbol>"));
    }
}
