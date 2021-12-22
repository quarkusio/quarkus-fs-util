package io.quarkus.fs.util.sysfs;

import io.quarkus.fs.util.base.DelegatingFileSystemProvider;
import io.quarkus.fs.util.base.DelegatingPath;
import java.io.IOException;
import java.nio.file.AccessMode;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Configurable File System Provider, which delegates all tasks to a delegate FSP, except for access mode checks.
 */
public class ConfigurableFileSystemProviderWrapper extends DelegatingFileSystemProvider {
    private final Set<AccessMode> allowedAccessModes;

    /**
     *
     * @param delegate the FileSystemProvider to delegate to. May not be null
     * @param allowedAccessModes The access modes which should be allowed by default. They given path won't be checked for
     *        these. May be null.
     */
    public ConfigurableFileSystemProviderWrapper(FileSystemProvider delegate, Set<AccessMode> allowedAccessModes) {
        super(delegate);
        this.allowedAccessModes = Objects.requireNonNullElse(allowedAccessModes, Collections.emptySet());
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        if (modes.length > 0 && !allowedAccessModes.isEmpty()) {
            List<AccessMode> accessModes = new ArrayList<>(3);
            for (AccessMode mode : modes) {
                if (!allowedAccessModes.contains(mode)) {
                    accessModes.add(mode);
                }
            }

            if (accessModes.isEmpty()) {
                return;
            }

            modes = accessModes.toArray(new AccessMode[0]);
        }

        delegate.checkAccess(DelegatingPath.unwrap(path), modes);
    }
}
