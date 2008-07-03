/*
 * Copyright (c) 2008 LabKey Corporation
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

import java.io.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;

/**
 * User: jeckels
 * Date: Apr 10, 2008
 */
public class ModuleExtractor
{
    private Collection<File> _moduleDirectories;
    private Set<File> _moduleFiles;

    private List<ModuleFileWatcher> _watchers = new ArrayList<ModuleFileWatcher>();

    public static final String MODULE_ARCHIVE_EXTENSION = ".module";

    public ModuleExtractor(Collection<File> moduleDirs)
    {
        _moduleDirectories = moduleDirs;
    }

    public Set<File> getModuleFiles()
    {
        return _moduleFiles;
    }

    public Set<File> examineModuleFiles()
    {
        FilenameFilter filter = new FilenameFilter()
        {
            public boolean accept(File dir, String name)
            {
                return name.toLowerCase().endsWith(MODULE_ARCHIVE_EXTENSION);
            }
        };

        Set<File> result = new LinkedHashSet<File>();

        for (File dir : _moduleDirectories)
        {
            File[] moduleArchives = dir.listFiles(filter);
            Arrays.sort(moduleArchives);
            result.addAll(Arrays.asList(moduleArchives));
        }

        return result;
    }

    /**
     *
     * @param webappDir target directory to write files into
     * @return list of JARs to put on the classpath
     */
    public ExtractionResult extractModules(File webappDir)
    {
        List<File> jarFiles = new ArrayList<File>();
        List<File> springConfigFiles = new ArrayList<File>();
        try
        {
            _moduleFiles = examineModuleFiles();

            for (File moduleArchive : _moduleFiles)
            {
                File moduleLibDir = new File(moduleArchive.getParentFile(), "moduleLibDir");
                ModuleFileWatcher moduleFileWatcher = new ModuleFileWatcher(moduleArchive, webappDir);

                JarFile f = null;
                try
                {
                    f = new JarFile(moduleArchive);
                    String moduleName = moduleArchive.getName().substring(0, moduleArchive.getName().length() - MODULE_ARCHIVE_EXTENSION.length());
                    File targetDir = new File(moduleLibDir, moduleName);

                    for (Enumeration<JarEntry> entries = f.entries(); entries.hasMoreElements(); )
                    {
                        JarEntry entry = entries.nextElement();
                        if (entry.getName().toLowerCase().startsWith("meta-inf/lib") && !entry.isDirectory())
                        {
                            File extractedFile = extractEntry(entry, f, targetDir);
                            moduleFileWatcher.addLibraryJar(extractedFile);
                            jarFiles.add(extractedFile);
                        }
                        else if (entry.getName().equalsIgnoreCase("web-inf/" + moduleName + "/" + moduleName + "context.xml"))
                        {
                            File extractedFile = extractEntry(entry, f, targetDir);
                            springConfigFiles.add(extractedFile);
                        }
                    }
                }
                finally
                {
                    if (f != null) { try { f.close(); } catch (IOException e) {}}
                }

                _watchers.add(moduleFileWatcher);
            }
            return new ExtractionResult(jarFiles, springConfigFiles);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    protected static File extractEntry(JarEntry entry, JarFile moduleArchive, File targetDir) throws IOException
    {
        targetDir.mkdirs();
        if (!targetDir.isDirectory())
        {
            throw new IOException("Failed to create directory " + targetDir.getPath() + ", the user account may not have sufficient permissions");
        }

        File destFile = new File(targetDir, entry.getName().substring(entry.getName().lastIndexOf('/') + 1));

        if (!destFile.exists() ||
            entry.getTime() == -1 ||
            entry.getTime() < (destFile.lastModified() - 2000) ||
            entry.getTime() > (destFile.lastModified() + 2000) ||
            entry.getSize() == -1 ||
            entry.getSize() != destFile.length())
        {
            BufferedInputStream bIn = null;
            BufferedOutputStream bOut = null;
            try
            {
                bIn = new BufferedInputStream(moduleArchive.getInputStream(entry));
                bOut = new BufferedOutputStream(new FileOutputStream(destFile));
                byte[] b = new byte[8192];
                int i;
                while ((i = bIn.read(b)) != -1)
                {
                    bOut.write(b, 0, i);
                }
            }
            finally
            {
                if (bIn != null) { try { bIn.close(); } catch (IOException e) {}}
                if (bOut != null) { try { bOut.close(); } catch (IOException e) {}}
            }
            if (entry.getTime() != -1)
            {
                destFile.setLastModified(entry.getTime());
            }
        }
        return destFile;
    }

    public List<ModuleFileWatcher> getWatchers()
    {
        return _watchers;
    }
}
