package com.fraudplatform.decision;

import java.nio.file.Files;
import java.nio.file.Path;

/** Repository paths for tests (Maven runs module tests with user.dir = the module directory). */
public final class TestPaths {

    public static final Path REPO = locateRepo();
    public static final Path MODELS = REPO.resolve("models");
    public static final Path CONFIG = REPO.resolve("config");

    private TestPaths() {
    }

    private static Path locateRepo() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("models"))) {
            p = p.getParent();
        }
        if (p == null) throw new IllegalStateException("repository root with models/ not found");
        return p;
    }
}
