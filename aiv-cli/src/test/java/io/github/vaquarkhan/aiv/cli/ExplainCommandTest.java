/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExplainCommandTest {

    @AfterEach
    void resetLoader() {
        ExplainCommand.resourceLoader = ExplainCommand.class.getClassLoader();
    }

    @Test
    void nullIdReturnsOne() {
        assertEquals(1, ExplainCommand.run(null));
    }

    @Test
    void blankIdReturnsOne() {
        assertEquals(1, ExplainCommand.run("   "));
    }

    @Test
    void unknownIdReturnsOne() {
        assertEquals(1, ExplainCommand.run("no-such-gate-xyz"));
    }

    @Test
    void embeddedHelpWithTrailingNewlineDoesNotAddBlankLine() {
        assertEquals(0, ExplainCommand.run("density"));
    }

    @Test
    void findingRuleIdFallsBackToGateHelp() {
        assertEquals(0, ExplainCommand.run("density.ldr"));
    }

    @Test
    void nestedRuleIdFallsBackToGateHelp() {
        assertEquals(0, ExplainCommand.run("design.forbidden.no-system-exit"));
    }

    @Test
    void helpWithoutTrailingNewlineStillPrintsOk(@TempDir Path root) throws Exception {
        Path md = root.resolve("explain/testnl.md");
        Files.createDirectories(md.getParent());
        Files.writeString(md, "x", StandardCharsets.UTF_8);
        try (URLClassLoader cl = new URLClassLoader(new URL[]{root.toUri().toURL()}, ExplainCommand.class.getClassLoader())) {
            ExplainCommand.resourceLoader = cl;
            assertEquals(0, ExplainCommand.run("testnl"));
        }
    }

    @Test
    void privateConstructorForCoverage() throws Exception {
        Constructor<ExplainCommand> c = ExplainCommand.class.getDeclaredConstructor();
        c.setAccessible(true);
        c.newInstance();
    }

    @Test
    void readIOExceptionReturnsOne() {
        ClassLoader mock = mock(ClassLoader.class);
        when(mock.getResourceAsStream(anyString())).thenReturn(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("boom");
            }
        });
        ExplainCommand.resourceLoader = mock;
        assertEquals(1, ExplainCommand.run("density"));
    }
}
