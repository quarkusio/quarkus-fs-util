package io.quarkus.fs.util.sysfs;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.fs.util.base.DelegatingFileSystemProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigurableFileSystemProviderWrapperTest {
    @Test
    void testAllowedAccessModes() throws IOException {
        Path tempFile = File.createTempFile("testAllowedAccessModes", "quarkus").toPath();
        DenyAllAccessModesFileSystemProvider denyAllFsp = new DenyAllAccessModesFileSystemProvider(
                tempFile.getFileSystem().provider());

        ConfigurableFileSystemProviderWrapper configurableFsp = new ConfigurableFileSystemProviderWrapper(denyAllFsp,
                Set.of(AccessMode.WRITE, AccessMode.EXECUTE));

        assertThrows(AccessDeniedException.class, () -> {
            configurableFsp.checkAccess(tempFile);
        });
        assertThrows(AccessDeniedException.class, () -> {
            configurableFsp.checkAccess(tempFile, AccessMode.READ);
        });

        assertDoesNotThrow(() -> {
            configurableFsp.checkAccess(tempFile, AccessMode.WRITE);
            configurableFsp.checkAccess(tempFile, AccessMode.EXECUTE);
        });
    }

    private class DenyAllAccessModesFileSystemProvider extends DelegatingFileSystemProvider {

        protected DenyAllAccessModesFileSystemProvider(FileSystemProvider delegate) {
            super(delegate);
        }

        @Override
        public void checkAccess(Path path, AccessMode... modes) throws IOException {
            throw new AccessDeniedException("Not allowed " + modes + " on " + path.toString());
        }
    }
}
