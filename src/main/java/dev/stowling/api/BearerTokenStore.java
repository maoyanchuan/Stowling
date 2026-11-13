package dev.stowling.api;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class BearerTokenStore {
    private static final int TOKEN_BYTES = 32;
    private static final int MIN_TOKEN_CHARACTERS = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private final Path tokenFile;

    public BearerTokenStore(Path tokenFile) {
        this.tokenFile = Objects.requireNonNull(tokenFile, "tokenFile")
                .toAbsolutePath()
                .normalize();
    }

    public String loadOrCreate() {
        try {
            Path parent = tokenFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                return createToken();
            } catch (FileAlreadyExistsException exception) {
                return loadToken();
            }
        } catch (IOException exception) {
            throw new TokenFileException("Unable to initialize the control token file", exception);
        }
    }

    private String createToken() throws IOException {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        createOwnerOnlyFile();
        restrictFileAccess();
        writeToken(token);
        return token;
    }

    private void createOwnerOnlyFile() throws IOException {
        FileAttribute<Set<PosixFilePermission>> permissions =
                PosixFilePermissionsAttribute.ownerOnly();
        try {
            Files.createFile(tokenFile, permissions);
        } catch (UnsupportedOperationException exception) {
            Files.createFile(tokenFile);
        }
    }

    private void restrictFileAccess() throws IOException {
        PosixFileAttributeView posixView = Files.getFileAttributeView(
                tokenFile,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (posixView != null) {
            posixView.setPermissions(OWNER_ONLY);
        }

        AclFileAttributeView aclView = Files.getFileAttributeView(
                tokenFile,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (aclView != null) {
            AclEntry ownerAccess = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(aclView.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            aclView.setAcl(List.of(ownerAccess));
        }

        DosFileAttributeView dosView = Files.getFileAttributeView(
                tokenFile,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (dosView != null) {
            dosView.setHidden(true);
        }
    }

    private void writeToken(String token) throws IOException {
        Set<OpenOption> options = Set.of(
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        try (SeekableByteChannel channel = Files.newByteChannel(tokenFile, options)) {
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(token + System.lineSeparator());
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    private String loadToken() throws IOException {
        if (!Files.isRegularFile(tokenFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new TokenFileException("The control token path is not a regular file");
        }
        restrictFileAccess();
        String contents = Files.readString(tokenFile, StandardCharsets.UTF_8);
        String token = removeSingleLineEnding(contents);
        if (token.length() < MIN_TOKEN_CHARACTERS || containsWhitespace(token)) {
            throw new TokenFileException("The control token file is invalid");
        }
        return token;
    }

    private static String removeSingleLineEnding(String contents) {
        String token = contents;
        if (token.endsWith("\r\n")) {
            token = token.substring(0, token.length() - 2);
        } else if (token.endsWith("\n")) {
            token = token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static boolean containsWhitespace(String value) {
        return value.codePoints().anyMatch(Character::isWhitespace);
    }

    private static final class PosixFilePermissionsAttribute {
        private PosixFilePermissionsAttribute() {
        }

        private static FileAttribute<Set<PosixFilePermission>> ownerOnly() {
            return new FileAttribute<>() {
                @Override
                public String name() {
                    return "posix:permissions";
                }

                @Override
                public Set<PosixFilePermission> value() {
                    return OWNER_ONLY;
                }
            };
        }
    }
}
