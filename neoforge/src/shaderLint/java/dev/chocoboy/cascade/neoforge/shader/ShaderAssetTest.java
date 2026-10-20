package dev.chocoboy.cascade.neoforge.shader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// the core shaders are compiled by the gpu backend on a real client launch only, and runGameTest is a
// dedicated server that never compiles them. this checks the source rules the compiler enforces, so a
// shader that would be rejected fails the build instead: the separate shader objects extension, includes
// spelled #include, explicit locations on every stage input and output, and the vulkan style vertex index
class ShaderAssetTest {

    private static final String EXTENSION = "#extension GL_ARB_separate_shader_objects : require";

    // the core shaders live in :common, shared by both loaders
    private static final Path CORE = coreDir();

    @Test
    void everyCoreShaderFollowsTheLayoutContract() {
        List<Path> shaders = shaders();
        assertFalse(shaders.isEmpty(), "expected the core shaders under " + CORE);
        List<String> problems = new ArrayList<>();
        for (Path shader : shaders) {
            check(shader, problems);
        }
        if (!problems.isEmpty()) {
            fail(String.join("\n", problems));
        }
    }

    private static void check(Path shader, List<String> problems) {
        String name = shader.getFileName().toString();
        String glsl = read(shader);
        if (!glsl.contains(EXTENSION)) {
            problems.add(name + ": missing '" + EXTENSION + "'");
        }
        if (glsl.contains("#moj_import")) {
            problems.add(name + ": uses #moj_import, which is spelled #include now");
        }
        if (glsl.contains("gl_VertexID")) {
            problems.add(name + ": uses gl_VertexID, which is gl_VertexIndex now");
        }
        for (String line : glsl.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("in ") || trimmed.startsWith("out ")) {
                problems.add(name + ": stage variable without an explicit location: " + trimmed);
            }
        }
    }

    private static List<Path> shaders() {
        try (Stream<Path> s = Files.list(CORE)) {
            return s.filter(p -> p.toString().endsWith(".vsh") || p.toString().endsWith(".fsh")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // gradle runs the test with the module dir as the working dir, but fall back a level so it also
    // works when a runner launches from the repo root
    private static Path coreDir() {
        String rel = "src/main/resources/assets/cascade/shaders/core";
        for (Path candidate : List.of(Path.of("../common/" + rel), Path.of("common/" + rel))) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return Path.of("../common/" + rel);
    }
}
