package dev.stowling.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HeadlessCommandRegistrationTest {
    @Test
    void registersOnlyDependencyFreeCommandClasses() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        try (InputStream input = loader.getResourceAsStream(
                "META-INF/hmc-specifics-commands"
        )) {
            assertTrue(input != null);
            String commands = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(commands.contains("dev.stowling.headless.Stowling"));
            assertTrue(commands.contains("Stowling dev.stowling.headless.Pause"));
            assertFalse(commands.contains("picocli"));
            assertEquals(0, Class.forName("dev.stowling.headless.Status")
                    .getDeclaredConstructors()[0]
                    .getParameterCount());
        }
    }
}
