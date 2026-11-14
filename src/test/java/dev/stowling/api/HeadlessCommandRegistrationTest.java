package dev.stowling.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.control.FakeControlService;
import dev.stowling.headless.HeadlessControlBridge;
import dev.stowling.headless.Pause;
import dev.stowling.headless.Resume;
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
            assertTrue(commands.contains("Stowling dev.stowling.headless.Status"));
            assertTrue(commands.contains("Stowling dev.stowling.headless.Reload"));
            assertFalse(commands.contains("dev.stowling.headless.Pause"));
            assertFalse(commands.contains("dev.stowling.headless.Resume"));
            assertFalse(commands.contains("picocli"));
            assertEquals(0, Class.forName("dev.stowling.headless.Status")
                    .getDeclaredConstructors()[0]
                    .getParameterCount());
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void removedCompatibilityCommandsCannotCallControlService() {
        FakeControlService service = new FakeControlService();
        HeadlessControlBridge.install(service);
        try {
            assertThrows(UnsupportedOperationException.class, () -> new Pause().call());
            assertThrows(UnsupportedOperationException.class, () -> new Resume().call());
            assertEquals(0, service.calls());
        } finally {
            HeadlessControlBridge.clear(service);
        }
    }
}
