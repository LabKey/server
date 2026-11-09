/*
 * Copyright (c) 2020-2026 LabKey Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.labkey.bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

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
