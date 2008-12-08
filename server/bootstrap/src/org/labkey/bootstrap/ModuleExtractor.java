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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * User: jeckels
 * Date: Apr 10, 2008
 */
public class ModuleExtractor
{
    private static Log _log = LogFactory.getLog(ModuleExtractor.class);
    private Collection<File> _moduleDirectories;
    private Set<File> _moduleFiles;
    private Set<File> _explodedModules = new LinkedHashSet<File>();

    private List<ModuleFileWatcher> _watchers = new ArrayList<ModuleFileWatcher>();
    private FilenameFilter _moduleFilter = new FilenameFilter()
        {
            public boolean accept(File dir, String name)
            {
                return name.toLowerCase().endsWith(MODULE_ARCHIVE_EXTENSION);
            }
        };

    private FilenameFilter _jarFilter = new FilenameFilter(){
                                public boolean accept(File dir, String name)
                                {
                                    String lowerName = name.toLowerCase();
                                    return lowerName.endsWith(".jar") && !(lowerName.endsWith("_jsp.jar"));
                                }
                            };

    private FilenameFilter _springContextFilter = new FilenameFilter(){
                                public boolean accept(File dir, String name)
                                {
                                    return name.toLowerCase().endsWith("context.xml");
                                }
                            };

    public static final String MODULE_ARCHIVE_EXTENSION = ".module";

    public ModuleExtractor(Collection<File> moduleDirs)
    {
        _moduleDirectories = moduleDirs;
    }

    public Set<File> getModuleFiles()
    {
        return _moduleFiles;
    }

    public Set<File> getExplodedModules()
    {
        return _explodedModules;
    }

    public Set<File> examineModuleFiles()
    {
        Set<File> result = new LinkedHashSet<File>();

        for (File dir : _moduleDirectories)
        {
            File[] moduleArchives = dir.listFiles(_moduleFilter);
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
            _log.info("Extracting modules...");

            for (File moduleFile : _moduleFiles)
            {
                String moduleName = moduleFile.getName().substring(0, moduleFile.getName().length() - MODULE_ARCHIVE_EXTENSION.length());
                File targetBaseDir = new File(moduleFile.getParentFile(), moduleName);
                if(!targetBaseDir.exists() || moduleFile.lastModified() > targetBaseDir.lastModified())
                    _log.info("Extracting the module " + moduleFile + "...");

                //if the moduleArchive is a compressed file, explode it into a peer directory
                if(moduleFile.isFile())
                {
                    JarFile f = null;
                    try
                    {
                        f = new JarFile(moduleFile);

                        //enumerate the entries and extract them all into an exploded form
                        for (Enumeration<JarEntry> entries = f.entries(); entries.hasMoreElements(); )
                        {
                            extractEntry(entries.nextElement(), f, targetBaseDir);
                        }

                        targetBaseDir.setLastModified(moduleFile.lastModified());

                        ModuleFileWatcher moduleFileWatcher = new ModuleFileWatcher(moduleFile, webappDir);

                        //add the JAR files from the exploded module dir to the module watcher
                        File libDir = new File(targetBaseDir, "lib");
                        if(libDir.exists())
                        {
                            for(File libFile : libDir.listFiles(_jarFilter))
                            {
                                moduleFileWatcher.addLibraryJar(libFile);
                            }
                        }

                        _watchers.add(moduleFileWatcher);

                    }
                    finally
                    {
                        if (f != null) { try { f.close(); } catch (IOException e) {}}
                    }
                }
            }

            //the .module files are now extracted into peer directories in the module directories
            //and there may also be other exploded module directories that were there already
            //iterrate over the module directories to build up the extraction result
            for(File dir : _moduleDirectories)
            {
                for(File explodedModuleDir : dir.listFiles())
                {
                    if(explodedModuleDir.isDirectory())
                    {
                        _explodedModules.add(explodedModuleDir);

                        //add any jar files to the jar files list
                        File libDir = new File(explodedModuleDir, "lib");
                        if(libDir.exists())
                            jarFiles.addAll(Arrays.asList(libDir.listFiles(_jarFilter)));

                        //add any Spring config files to the springConfigFiles list
                        File configDir = new File(explodedModuleDir, "config");
                        if(configDir.exists())
                            springConfigFiles.addAll(Arrays.asList(configDir.listFiles(_springContextFilter)));

                    }
                }
            }

            _log.info("Module extraction complete.");

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

        File destFile = new File(targetDir, entry.getName());

        //if entry is a directory, just make the dirs and return
        if(entry.isDirectory())
        {
            destFile.mkdirs();
            return destFile;
        }

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

    /**
     * Extract .module files
     * @param args
     */
    public static void main(String... args) throws ConfigException, IOException
    {
        try
        {
            PipelineBootstrapConfig config = new PipelineBootstrapConfig(args);

            ModuleExtractor extractor = new ModuleExtractor(Collections.singleton(config.getModulesDir())
            );
            extractor.extractModules(config.getWebappDir());
        }
        catch (ConfigException e)
        {
            printUsage(e.getMessage());
        }
    }

    private static void printUsage(String error)
    {
        if (error != null)
        {
            System.err.println(error);
            System.err.println();
        }

        System.err.println("java " + ModuleExtractor.class + " [-" + PipelineBootstrapConfig.MODULES_DIR + "=<MODULE_DIR>] [-" + PipelineBootstrapConfig.WEBAPP_DIR + "=<WEBAPP_DIR>] [-" + PipelineBootstrapConfig.CONFIG_DIR + "=<CONFIG_DIR>]");

        System.exit(1);
    }
}
