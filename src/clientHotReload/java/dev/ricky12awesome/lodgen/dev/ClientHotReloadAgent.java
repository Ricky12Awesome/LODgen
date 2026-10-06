package dev.ricky12awesome.lodgen.dev;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletionStage;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Development-only source watcher, launched by runClient rather than shipped with the mod. */
public final class ClientHotReloadAgent {
    private final Instrumentation instrumentation;
    private final Properties config;
    private final List<Path> javaRoots;
    private final List<Path> preprocessRoots;
    private final List<Path> resourceRoots;
    private final Path classOutput;
    private final Path resourceOutput;
    private final Path stagingDirectory;
    private Map<String, byte[]> liveClasses;
    private Map<Path, String> goodSources;
    private volatile boolean reloadRequested;
    private volatile boolean reloadInFlight;
    private volatile long nextReloadAttempt;

    private ClientHotReloadAgent(Properties config, Instrumentation instrumentation) throws Exception {
        this.config = config;
        this.instrumentation = instrumentation;
        javaRoots = roots("javaRoots");
        preprocessRoots = roots("preprocessRoots");
        resourceRoots = roots("resourceRoots");
        classOutput = Path.of(config.getProperty("classOutput"));
        resourceOutput = Path.of(config.getProperty("resourceOutput"));
        stagingDirectory = Path.of(config.getProperty("stagingDirectory"));
        liveClasses = classBytes(classOutput);
        goodSources = sourceSnapshot();
    }

    public static void premain(String argument, Instrumentation instrumentation) {
        try {
            Properties config = new Properties();
            try (InputStream input = Files.newInputStream(Path.of(argument))) {
                config.load(input);
            }
            ClientHotReloadAgent agent = new ClientHotReloadAgent(config, instrumentation);
            Thread watcher = new Thread(agent::watch, "lodgen-client-hot-reload");
            watcher.setDaemon(true);
            watcher.start();
            log("Watching Java and resources; save changes to reload the running client.");
        } catch (Throwable failure) {
            log("Could not start source watcher: " + describe(failure));
        }
    }

    private void watch() {
        Map<Path, String> observedSources = goodSources;
        Map<String, Resource> observedResources;
        try {
            observedResources = resourceSnapshot();
        } catch (Exception failure) {
            log("Could not read initial resources: " + describe(failure));
            observedResources = Map.of();
        }
        Map<String, Resource> appliedResources = observedResources;
        long sourceChangedAt = 0;
        long resourceChangedAt = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(500);
                long now = System.currentTimeMillis();
                Map<Path, String> sources = sourceSnapshot();
                if (!sources.equals(observedSources)) {
                    observedSources = sources;
                    sourceChangedAt = now;
                } else if (sourceChangedAt != 0 && now - sourceChangedAt >= 500) {
                    sourceChangedAt = 0;
                    compileAndReload(sources);
                }
                Map<String, Resource> resources = resourceSnapshot();
                if (!resources.equals(observedResources)) {
                    observedResources = resources;
                    resourceChangedAt = now;
                } else if (resourceChangedAt != 0 && now - resourceChangedAt >= 500) {
                    if (copyResources(appliedResources, resources)) {
                        appliedResources = resources;
                        resourceChangedAt = 0;
                    }
                }
                scheduleResourceReload();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                log("Watcher will retry after an I/O error: " + describe(failure));
            }
        }
    }

    private void compileAndReload(Map<Path, String> sources) {
        if (!sources.keySet().containsAll(goodSources.keySet())) {
            log("A Java source was deleted or renamed; restart the client. Keeping the last good classes.");
            return;
        }
        Path attempt = null;
        Map<Path, Path> prepared = new LinkedHashMap<>();
        Map<String, Path> publishedNew = new LinkedHashMap<>();
        boolean redefinitionSucceeded = false;
        try {
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                log("Java reload needs a JDK with javac; restart runClient using a JDK.");
                return;
            }
            Files.createDirectories(stagingDirectory);
            attempt = Files.createTempDirectory(stagingDirectory, "compile-");
            Path classes = Files.createDirectories(attempt.resolve("classes"));
            List<Path> inputs = new ArrayList<>();
            for (Path root : javaRoots) inputs.addAll(javaFiles(root));
            Map<Path, Path> preprocessed = new LinkedHashMap<>();
            for (Path root : preprocessRoots) {
                for (Path source : javaFiles(root)) {
                    Path relative = root.relativize(source);
                    Path output = attempt.resolve("sources").resolve(relative);
                    Files.createDirectories(output.getParent());
                    Files.writeString(output, preprocess(source), StandardCharsets.UTF_8);
                    preprocessed.put(relative, output);
                }
            }
            inputs.addAll(preprocessed.values());
            if (inputs.isEmpty()) {
                log("No Java sources remain; restart the client.");
                return;
            }
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                List<String> options = List.of("-g", "-encoding", "UTF-8", "--release",
                        config.getProperty("release", "21"), "-classpath",
                        config.getProperty("compileClasspath", ""), "-d", classes.toString(), "-proc:none");
                boolean success = compiler.getTask(null, manager, diagnostics, options, null,
                        manager.getJavaFileObjectsFromPaths(inputs)).call();
                if (!success) {
                    log("Compilation failed; keeping the last good classes:");
                    diagnostics.getDiagnostics().forEach(diagnostic -> log(diagnostic.toString()));
                    return;
                }
            }
            Map<String, byte[]> compiled = classBytes(classes);
            if (!compiled.keySet().containsAll(liveClasses.keySet())) {
                log("Compilation removed a class; restart the client. Keeping the last good classes.");
                return;
            }
            Map<String, byte[]> changed = new LinkedHashMap<>();
            compiled.forEach((name, bytes) -> {
                if (!Arrays.equals(bytes, liveClasses.get(name))) changed.put(name, bytes);
            });
            List<ClassDefinition> definitions = new ArrayList<>();
            var redefinedNames = new HashSet<String>();
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                byte[] bytes = changed.get(loaded.getName());
                if (bytes != null) {
                    if (!instrumentation.isModifiableClass(loaded)) {
                        log("Class " + loaded.getName() + " cannot be redefined; restart the client.");
                        return;
                    }
                    definitions.add(new ClassDefinition(loaded, bytes));
                    redefinedNames.add(loaded.getName());
                }
            }
            // Prepare complete files before changing any loaded class or live output file.
            for (var entry : changed.entrySet()) {
                Path output = classOutput.resolve(entry.getKey().replace('.', '/') + ".class");
                Files.createDirectories(output.getParent());
                Path temporary = Files.createTempFile(output.getParent(), ".hot-reload-", ".tmp");
                prepared.put(output, temporary);
                Files.write(temporary, entry.getValue());
            }
            if (!definitions.isEmpty() && !instrumentation.isRedefineClassesSupported()) {
                log("This JVM cannot redefine classes; restart the client to apply Java changes.");
                return;
            }
            // Updated methods may execute immediately after redefine and load new helper classes.
            for (String name : changed.keySet()) {
                if (!liveClasses.containsKey(name) && !redefinedNames.contains(name)) {
                    Path output = classOutput.resolve(name.replace('.', '/') + ".class");
                    if (Files.exists(output)) throw new IOException("Class output changed externally: " + output);
                    atomicReplace(prepared.get(output), output);
                    publishedNew.put(name, output);
                }
            }
            if (!definitions.isEmpty()) {
                instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
            }
            redefinitionSucceeded = true;
            for (var entry : prepared.entrySet()) {
                if (Files.exists(entry.getValue())) atomicReplace(entry.getValue(), entry.getKey());
            }
            liveClasses = compiled;
            goodSources = sources;
            log("Java reload succeeded: " + definitions.size() + " loaded classes updated, "
                    + (changed.size() - redefinedNames.size()) + " class files ready for later loading.");
        } catch (Throwable failure) {
            if (redefinitionSucceeded) {
                log("Java classes were accepted, but publishing class files failed: " + describe(failure)
                        + ". Restart the client to synchronize the running classes and disk output.");
            } else {
                log("Java reload rejected; keeping existing classes. " + describe(failure)
                        + ". Adding/removing fields or methods, or changing class structure, requires a client restart.");
            }
        } finally {
            if (!redefinitionSucceeded && !publishedNew.isEmpty()) {
                var loadedNames = new HashSet<String>();
                for (Class<?> loaded : instrumentation.getAllLoadedClasses()) loadedNames.add(loaded.getName());
                for (var entry : publishedNew.entrySet()) {
                    if (loadedNames.contains(entry.getKey())) {
                        log("New class " + entry.getKey() + " loaded during the rejected reload; restart to unload it.");
                    } else {
                        try { Files.deleteIfExists(entry.getValue()); } catch (IOException failure) {
                            log("Could not remove staged new class " + entry.getKey() + "; restart: " + describe(failure));
                        }
                    }
                }
            }
            for (Path temporary : prepared.values()) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
            if (attempt != null) {
                try { deleteTree(attempt); } catch (IOException ignored) { }
            }
        }
    }

    private String preprocess(Path source) throws IOException {
        List<Boolean> active = new ArrayList<>(List.of(true));
        StringBuilder output = new StringBuilder();
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            String directive = line.trim().replaceFirst("^//#", "// #");
            int last = active.size() - 1;
            if (directive.startsWith("// #if ")) {
                String flag = directive.substring(7);
                String value = config.getProperty("flag." + flag);
                if (value == null) throw new IOException("Unknown preprocessor flag " + flag + " in " + source);
                active.add(active.get(last) && Boolean.parseBoolean(value));
            } else if (directive.equals("// #else")) {
                if (last == 0) throw new IOException("Unexpected #else in " + source);
                active.set(last, active.get(last - 1) && !active.get(last));
            } else if (directive.equals("// #endif")) {
                if (last == 0) throw new IOException("Unexpected #endif in " + source);
                active.remove(last);
            } else if (active.get(last)) {
                output.append(line).append('\n');
            }
        }
        if (active.size() != 1) throw new IOException("Unclosed condition in " + source);
        return output.toString();
    }

    private boolean copyResources(Map<String, Resource> previous, Map<String, Resource> current) {
        boolean assetsChanged = false;
        int changed = 0;
        try {
            for (String name : previous.keySet()) {
                if (!current.containsKey(name)) {
                    Files.deleteIfExists(resourceOutput.resolve(name));
                    assetsChanged |= name.startsWith("assets/");
                    changed++;
                }
            }
            for (var entry : current.entrySet()) {
                if (!entry.getValue().equals(previous.get(entry.getKey()))) {
                    Path output = resourceOutput.resolve(entry.getKey());
                    Files.createDirectories(output.getParent());
                    Path temporary = Files.createTempFile(output.getParent(), ".hot-reload-", ".tmp");
                    try {
                        Files.copy(entry.getValue().source(), temporary, StandardCopyOption.REPLACE_EXISTING);
                        atomicReplace(temporary, output);
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                    assetsChanged |= entry.getKey().startsWith("assets/");
                    changed++;
                }
            }
            log("Updated " + changed + " resource files.");
            return true;
        } catch (IOException failure) {
            log("Resource copy failed; will retry: " + describe(failure));
            return false;
        } finally {
            if (assetsChanged) reloadRequested = true;
        }
    }

    private void scheduleResourceReload() {
        if (!reloadRequested || reloadInFlight || System.currentTimeMillis() < nextReloadAttempt) return;
        nextReloadAttempt = System.currentTimeMillis() + 2000;
        // Looking through loaded classes avoids initializing Minecraft before the mod loader is ready.
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (!loaded.getName().equals("net.minecraft.client.Minecraft")) continue;
            try {
                Object client = loaded.getMethod("getInstance").invoke(null);
                if (client == null) return;
                reloadInFlight = true;
                reloadRequested = false;
                loaded.getMethod("execute", Runnable.class).invoke(client, (Runnable) () -> {
                    try {
                        Object result = loaded.getMethod("reloadResourcePacks").invoke(client);
                        if (result instanceof CompletionStage<?> completion) {
                            completion.whenComplete((unused, failure) -> finishResourceReload(failure));
                        } else {
                            finishResourceReload(null);
                        }
                    } catch (Throwable failure) {
                        finishResourceReload(failure);
                    }
                });
            } catch (Throwable failure) {
                finishResourceReload(failure);
            }
            return;
        }
    }

    private void finishResourceReload(Throwable failure) {
        if (failure == null) {
            log("Client resource reload completed.");
        } else {
            log("Client resource reload will retry when ready: " + describe(failure));
            reloadRequested = true;
        }
        reloadInFlight = false;
    }

    private List<Path> roots(String key) {
        return config.getProperty(key, "").lines().filter(line -> !line.isBlank()).map(Path::of).toList();
    }

    private Map<Path, String> sourceSnapshot() throws Exception {
        Map<Path, String> snapshot = new HashMap<>();
        for (Path root : javaRoots) for (Path source : javaFiles(root)) snapshot.put(source, hash(source));
        for (Path root : preprocessRoots) for (Path source : javaFiles(root)) snapshot.put(source, hash(source));
        return snapshot;
    }

    private Map<String, Resource> resourceSnapshot() throws Exception {
        Map<String, Resource> snapshot = new LinkedHashMap<>();
        for (Path root : resourceRoots) {
            if (!Files.isDirectory(root)) continue;
            try (var paths = Files.walk(root)) {
                for (Path source : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String name = root.relativize(source).toString().replace('\\', '/');
                    if (restartOnlyResource(name)) continue;
                    if (!snapshot.containsKey(name)) snapshot.put(name, new Resource(source, hash(source)));
                }
            }
        }
        return snapshot;
    }

    private static boolean restartOnlyResource(String name) {
        return name.equals("fabric.mod.json") || name.equals("META-INF/neoforge.mods.toml")
                || name.equals("lodgen.target.properties") || name.endsWith(".mixins.json");
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static Map<String, byte[]> classBytes(Path root) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) return classes;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".class")).sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                classes.put(relative.substring(0, relative.length() - 6).replace('/', '.'), Files.readAllBytes(path));
            }
        }
        return classes;
    }

    private static String hash(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void atomicReplace(Path temporary, Path output) throws IOException {
        try {
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Output filesystem does not support atomic replacement: " + output, unsupported);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static String describe(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    private static void log(String message) {
        System.out.println("[LODgen hot reload] " + message);
    }

    private record Resource(Path source, String hash) { }
}
