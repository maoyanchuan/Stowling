package dev.stowling.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BearerTokenStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesAndPersistsSeparateTokenFile() throws Exception {
        Path tokenFile = temporaryDirectory.resolve("control.token");
        BearerTokenStore store = new BearerTokenStore(tokenFile);

        String created = store.loadOrCreate();
        String loaded = store.loadOrCreate();

        assertEquals(created, loaded);
        assertTrue(created.length() >= 32);
        assertEquals(created, Files.readString(tokenFile).trim());

        PosixFileAttributeView view = Files.getFileAttributeView(
                tokenFile,
                PosixFileAttributeView.class
        );
        if (view != null) {
            assertEquals(
                    Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ),
                    view.readAttributes().permissions()
            );
        }
    }
}
