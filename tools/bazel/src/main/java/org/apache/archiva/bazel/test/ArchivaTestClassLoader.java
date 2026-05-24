/*
 * Bazel test helper that papers over the Maven test-classes layout assumption
 * baked into many archiva tests.
 *
 * Many tests call:
 *   Thread.currentThread().getContextClassLoader().getResource("foo").toURI()
 *   Paths.get(uri)
 * which works when classpath resources live as loose files (Maven's
 * target/test-classes), but fails under Bazel because java_library packs
 * resources into a jar -- getResource() returns a jar:file:...!/foo URI
 * that Paths.get(URI) rejects.
 *
 * Installed as -Djava.system.class.loader. At construction time we copy
 * the runfiles-located test resources to TEST_TMPDIR (or fall back to
 * java.io.tmpdir) and prepend that copy to the URL search path. Resource
 * lookups then return a file: URL pointing at the copy, so:
 *   - getResource().toURI() -> Paths.get() works.
 *   - Tests that write back to a getResource()-derived path mutate the
 *     copy in TEST_TMPDIR rather than the original workspace fixture.
 *
 * Resource lookups for files NOT under the configured directory fall through
 * to the parent class loader (jar entries continue to work).
 */
package org.apache.archiva.bazel.test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public final class ArchivaTestClassLoader extends URLClassLoader {

    private static final String RESOURCE_DIR_PROP = "archiva.bazel.test.resources.dir";

    public ArchivaTestClassLoader(ClassLoader parent) {
        super(stagingUrls(), parent);
    }

    private static URL[] stagingUrls() {
        String src = System.getProperty(RESOURCE_DIR_PROP);
        if (src == null || src.isEmpty()) {
            return new URL[0];
        }
        Path srcDir = Paths.get(src).toAbsolutePath();
        if (!Files.isDirectory(srcDir)) {
            // Configured dir doesn't exist (e.g. module has no test resources);
            // silently no-op rather than fail every test.
            return new URL[0];
        }
        try {
            Path stage = stagingRoot().resolve("archiva-test-resources");
            if (Files.exists(stage)) {
                deleteRecursively(stage);
            }
            copyTree(srcDir, stage);
            // Trailing slash is required by URLClassLoader to treat it as a directory.
            return new URL[]{new java.io.File(stage.toString() + "/").toURI().toURL()};
        } catch (IOException e) {
            throw new RuntimeException("Failed to stage test resources from " + srcDir, e);
        }
    }

    private static Path stagingRoot() {
        String tmp = System.getenv("TEST_TMPDIR");
        if (tmp == null || tmp.isEmpty()) {
            tmp = System.getProperty("java.io.tmpdir");
        }
        return Paths.get(tmp);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Resources: check our staged copy first, fall through to parent for jar entries. */
    @Override
    public URL getResource(String name) {
        URL u = findResource(name);
        if (u != null) {
            return u;
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> all = new ArrayList<>();
        Enumeration<URL> own = findResources(name);
        while (own.hasMoreElements()) {
            all.add(own.nextElement());
        }
        Enumeration<URL> parents = getParent().getResources(name);
        while (parents.hasMoreElements()) {
            all.add(parents.nextElement());
        }
        return Collections.enumeration(all);
    }

    @Override
    public String toString() {
        return "ArchivaTestClassLoader" + Arrays.toString(getURLs());
    }
}
