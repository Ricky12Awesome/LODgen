package dev.ricky12awesome.lodgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ClientHotReloadAgentTest {
    @TempDir Path directory;

    @Test
    void reloadsSavedCodeAndResourcesAndKeepsLastGoodCodeOnErrors() throws Exception {
        Path sources = directory.resolve("sources");
        Path preprocessed = directory.resolve("preprocessed");
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Path resources = Files.createDirectories(directory.resolve("resources"));
        Path resourceOutput = Files.createDirectories(directory.resolve("resource-output"));
        Path value = write(preprocessed.resolve("fixture/Value.java"), valueSource("before", ""));
        Path main = write(sources.resolve("fixture/Main.java"), """
                package fixture;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        net.minecraft.client.Minecraft.getInstance();
                        while (true) {
                            System.out.println("VALUE=" + Value.value());
                            Thread.sleep(100);
                        }
                    }
                }
                """);
        Path minecraft = write(sources.resolve("net/minecraft/client/Minecraft.java"), """
                package net.minecraft.client;
                public class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    public static Minecraft getInstance() { return INSTANCE; }
                    public void execute(Runnable task) { task.run(); }
                    public java.util.concurrent.CompletableFuture<Void> reloadResourcePacks() {
                        try (var input = getClass().getResourceAsStream("/assets/example.txt")) {
                            System.out.println("RELOAD=" + (input == null ? "missing" :
                                new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                        } catch (Exception failure) { throw new RuntimeException(failure); }
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-g", "-d", classes.toString(), main.toString(), minecraft.toString(), value.toString()));
        write(value, """
                package fixture;
                public final class Value {
                    public static String value() {
                        // #if MC_1211
                        return "before";
                        // #else
                        return "wrong branch";
                        // #endif
                    }
                }
                """);
        Path asset = write(resources.resolve("assets/example.txt"), "before");
        write(resourceOutput.resolve("assets/example.txt"), "before");
        write(resources.resolve("fabric.mod.json"), "source metadata");
        Path metadata = write(resourceOutput.resolve("fabric.mod.json"), "expanded metadata");

        Properties config = new Properties();
        config.setProperty("javaRoots", sources.toString());
        config.setProperty("preprocessRoots", preprocessed.toString());
        config.setProperty("resourceRoots", resources.toString());
        config.setProperty("classOutput", classes.toString());
        config.setProperty("resourceOutput", resourceOutput.toString());
        config.setProperty("stagingDirectory", directory.resolve("staging").toString());
        config.setProperty("compileClasspath", classes.toString());
        config.setProperty("release", Integer.toString(Runtime.version().feature()));
        config.setProperty("flag.MC_1211", "true");
        Path configFile = directory.resolve("client.properties");
        try (var output = Files.newOutputStream(configFile)) { config.store(output, "Fixture"); }
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String agent = System.getProperty("lodgen.hotReloadAgentJar");
        assertNotNull(agent, "Gradle must provide the development agent jar");
        Process client = new ProcessBuilder(javaExecutable, "-javaagent:" + agent + "=" + configFile,
                "-cp", classes + java.io.File.pathSeparator + resourceOutput, "fixture.Main")
                .redirectErrorStream(true).start();
        LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        List<String> transcript = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            try (var output = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
                for (String line; (line = output.readLine()) != null;) {
                    transcript.add(line);
                    lines.add(line);
                }
            } catch (Exception ignored) { }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            await(lines, transcript, "VALUE=before");
            write(value, Files.readString(value).replace("return \"before\"", "return \"after\""));
            await(lines, transcript, "VALUE=after");

            write(sources.resolve("fixture/Helper.java"), """
                    package fixture;
                    public final class Helper {
                        public static String value() { return "from helper"; }
                    }
                    """);
            write(value, Files.readString(value).replace("return \"after\"", "return Helper.value()"));
            await(lines, transcript, "VALUE=from helper");
            byte[] lastGood = Files.readAllBytes(classes.resolve("fixture/Value.class"));

            write(value, valueSource("broken", "").replace("return \"broken\";", "return ;"));
            await(lines, transcript, "compilation failed");
            assertArrayEquals(lastGood, Files.readAllBytes(classes.resolve("fixture/Value.class")));
            await(lines, transcript, "VALUE=from helper");

            write(asset, "edited");
            await(lines, transcript, "RELOAD=edited");
            assertEquals("edited", Files.readString(resourceOutput.resolve("assets/example.txt")));
            Files.delete(asset);
            await(lines, transcript, "RELOAD=missing");
            assertFalse(Files.exists(resourceOutput.resolve("assets/example.txt")));

            write(resources.resolve("fabric.mod.json"), "edited metadata");
            write(value, valueSource("incompatible", "public int addedField;"));
            await(lines, transcript, "restart");
            assertArrayEquals(lastGood, Files.readAllBytes(classes.resolve("fixture/Value.class")));
            assertEquals("expanded metadata", Files.readString(metadata));

            write(value, valueSource("recovered", ""));
            await(lines, transcript, "VALUE=recovered");
            assertTrue(client.isAlive(), String.join("\n", transcript));
        } finally {
            client.destroy();
            if (!client.waitFor(5, TimeUnit.SECONDS)) client.destroyForcibly().waitFor();
            reader.join(1000);
        }
    }

    private static String valueSource(String value, String members) {
        return "package fixture; public final class Value { " + members
                + " public static String value() { return \"" + value + "\"; } }\n";
    }

    private static Path write(Path file, String contents) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }

    private static void await(LinkedBlockingQueue<String> lines, List<String> transcript, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (line != null && line.toLowerCase(java.util.Locale.ROOT).contains(expected.toLowerCase(java.util.Locale.ROOT))) return;
        }
        fail("Timed out waiting for " + expected + ":\n" + String.join("\n", transcript));
    }
}
