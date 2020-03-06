package org.labkey.bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/* NOTE: these two files need to be the same
    server/bootstrap/src/org/labkey/bootstrap/ExplodedModuleService.java
    server/modules/platform/api/src/org/labkey/bootstrap/ExplodedModuleService.java
  TODO: figure out how to update the build to share this file
 */

/** This interface provides for 'exploding' .module files as well as describing known module directories */

public interface ExplodedModuleService
{
    List<File> getExplodedModuleDirectories();

    // Using Map.Entry to avoid needing to proxy an inner interface/class
    // returns <@NotNull Directory, @Nullable Module>
    List<Map.Entry<File,File>> getExplodedModules();

    Map.Entry<File,File> updateModule(File explodedModuleDirectory, File updateArchive, File existingArchive, File mvExistingArchive, boolean dryRun) throws IOException;

    Map.Entry<File,File> newModule(File updateArchive, File target) throws IOException;

    File getExternalModulesDirectory();

    File getDeletedModulesDirectory();
}
