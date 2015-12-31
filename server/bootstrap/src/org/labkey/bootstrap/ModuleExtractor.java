/*
 * Copyright (c) 2008-2015 LabKey Corporation
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

/**
 * Extracts modules into exploded module directories
 */
public class ModuleExtractor
{
    public final FilenameFilter moduleArchiveFilter = (dir, name) -> name.toLowerCase().endsWith(ModuleArchive.FILE_EXTENSION);

    protected final File _webAppDirectory;
    protected final ModuleDirectories _moduleDirectories;

    private Set<File> _moduleArchiveFiles;
    private Map<File, Long> _errorArchives;
    private Set<File> _ignoredExplodedDirs;
    private Set<ExplodedModule> _explodedModules;

    private final SimpleLogger _log;

    public ModuleExtractor(File webAppDirectory, SimpleLogger log)
    {
        _webAppDirectory = webAppDirectory;
        _moduleDirectories = new ModuleDirectories(_webAppDirectory);
        _log = log;
    }

    public Collection<ExplodedModule> extractModules()
    {
        Set<File> webAppFiles = getWebAppFiles();
        _moduleArchiveFiles = new HashSet<>();
        _errorArchives = new HashMap<>();
        _ignoredExplodedDirs = new HashSet<>();

        //explode all module archives
        for(File moduleDir : _moduleDirectories.getAllModuleDirectories())
        {
            for(File moduleArchiveFile : moduleDir.listFiles(moduleArchiveFilter))
            {
                try
                {
                    ModuleArchive moduleArchive = new ModuleArchive(moduleArchiveFile, _log);
                    moduleArchive.extractAll();
                    _moduleArchiveFiles.add(moduleArchiveFile);
                }
                catch(IOException e)
                {
                    _log.error("Unable to extract the module archive " + moduleArchiveFile.getPath() + "!", e);
                    _errorArchives.put(moduleArchiveFile, moduleArchiveFile.lastModified());
                }
            }
        }

        //gather all the exploded module directories
        _log.info("Deploying resources from exploded modules to web app directory...");
        // This needs to be a linked HashSet so that we preserve the order and handle the core modules before
        // the ones in the external modules directory, in case there are any duplicates
        _explodedModules = new LinkedHashSet<>();
        for(File moduleDir : _moduleDirectories.getAllModuleDirectories())
        {
            for(File dir : moduleDir.listFiles())
            {
                if(dir.isDirectory())
                {
                    if (dir.isHidden())
                    {
                        _ignoredExplodedDirs.add(dir);
                        continue;
                    }

                    try
                    {
                        ExplodedModule explodedModule = new ExplodedModule(dir);
                        Set<File> moduleWebAppFiles = explodedModule.deployToWebApp(_webAppDirectory);
                        if (null != webAppFiles)
                            webAppFiles.addAll(moduleWebAppFiles);

                        _explodedModules.add(explodedModule);
                    }
                    catch(IOException e)
                    {
                        _log.error("Unable to deploy the resources from the exploded module " + dir.getPath() + " to the web app directory!", e);
                    }
                }
            }
        }

        _log.info("Module extraction and deployment complete.");
        if (null != webAppFiles)
            cleanupWebAppDir(webAppFiles);

        return _explodedModules;
    }

    private void cleanupWebAppDir(Set<File> allowedFiles)
    {
        //delete any file we find in the web app directory that is not in the webAppFiles set
        _log.info("Cleaning up web app directory...");
        cleanupDir(_webAppDirectory, allowedFiles);
        _log.info("Web app directory cleanup complete.");
    }

    private void cleanupDir(File dir, Set<File> allowedFiles)
    {
        for (File file : dir.listFiles())
        {
            if (file.isDirectory())
            {
                cleanupDir(file, allowedFiles);
            }

            if (!allowedFiles.contains(file))
            {
                _log.info("Deleting unused file in web app directory: " + file.getAbsolutePath());
                if (!file.delete())
                    _log.info("WARNING: unable to delete unused web app file " + file.getAbsolutePath());
            }
        }
    }

    /** @return null if there was a problem (likely file system permissions) that prevents us from reading the directory */
    protected Set<File> getWebAppFiles()
    {
        //load the apiFiles.list to get a list of all files that are part of the core web app
        File apiFiles = new File(_webAppDirectory, DirectoryFileListWriter.API_FILES_LIST_RELATIVE_PATH);
        if (!apiFiles.exists())
        {
            _log.info("WARNING: could not find the list of web app files at " + apiFiles.getPath() + ". Automatic cleanup of the web app directory will not occur.");
            return null;
        }

        //file contains one path per line
        Set<File> files = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(apiFiles)))
        {
            String line;
            while (null != (line = reader.readLine()))
            {
                files.add(new File(_webAppDirectory, line));
            }
        }
        catch (Exception e)
        {
            _log.info("WARNING: exception while reading " + apiFiles.getPath() + ". "  + e.toString());
            return null;
        }

        return files;
    }


    public List<File> getExplodedModuleDirectories()
    {
        List<File> dirs = new ArrayList<>();
        for(ExplodedModule expMod : _explodedModules)
        {
            dirs.add(expMod.getRootDirectory());
        }
        return dirs;
    }

    private void logModuleMessage(String module, Set<String> previouslyLoggedModules, String message)
    {
        logModuleMessage(module, previouslyLoggedModules, message, null);
    }

    /** Logs the mesage if we haven't previously logged a message about that module */
    private void logModuleMessage(String module, Set<String> previouslyLoggedModules, String message, Throwable t)
    {
        if (!previouslyLoggedModules.contains(module))
        {
            if (t == null)
            {
                _log.info(message);
            }
            else
            {
                _log.error(message, t);
            }
            previouslyLoggedModules.add(module);
        }
    }

    /** @param previouslyLoggedModules module names which have already been logged about since we started up the webapp */
    public boolean areModulesModified(Set<String> previouslyLoggedModules)
    {
        if(null == _explodedModules)
        {
            logModuleMessage(null, previouslyLoggedModules, "ModuleExtractor not initialized as expected. Previous extraction may have failed.");
            return true;
        }

        boolean modified = false;
        //check module archives against exploded modules and check for new modules
        for(File moduleDir : _moduleDirectories.getAllModuleDirectories())
        {
            for(File moduleArchiveFile : moduleDir.listFiles(moduleArchiveFilter))
            {
                //if this errored last time and it hasn't changed, just skip it
                if (_errorArchives.containsKey(moduleArchiveFile)
                        && _errorArchives.get(moduleArchiveFile).longValue() == moduleArchiveFile.lastModified())
                    continue;

                //if it's a new module, return true
                if(!_moduleArchiveFiles.contains(moduleArchiveFile))
                {
                    logModuleMessage(moduleArchiveFile.getName(), previouslyLoggedModules, "New module archive '" + moduleArchiveFile.getPath() + "' found...");
                    modified = true;
                }

                //if it's been modified since extraction, re-extract it
                ModuleArchive moduleArchive = new ModuleArchive(moduleArchiveFile, _log);
                if(moduleArchive.isModified())
                {
                    try
                    {
                        File explodedDir = moduleArchive.extractAll();
                        new ExplodedModule(explodedDir).deployToWebApp(_webAppDirectory);
                    }
                    catch(IOException e)
                    {
                        logModuleMessage(moduleArchiveFile.getName(), previouslyLoggedModules, "Could not re-extract module " + moduleArchive.getModuleName() + ".", e);
                        modified = true;
                    }
                }
            }

            //check for new exploded modules
            for(File dir : moduleDir.listFiles())
            {
                if(dir.isDirectory())
                {
                    //if this is in the set of ignored dirs, ignore it
                    if (_ignoredExplodedDirs.contains(dir))
                        continue;

                    ExplodedModule explodedModule = new ExplodedModule(dir);
                    if(!_explodedModules.contains(explodedModule))
                    {
                        logModuleMessage(dir.getName(), previouslyLoggedModules, "New module directory '" + dir.getPath() + "' found.");
                        modified = true;
                    }
                }
            }
        }

        //check existing exploded modules
        for(ExplodedModule explodedModule : _explodedModules)
        {
            if(explodedModule.isModified())
            {
                logModuleMessage(explodedModule.getRootDirectory().getName(), previouslyLoggedModules, "Module '" + explodedModule.getRootDirectory().getName() + "' has been modified.");
                modified = true;
            }

            //if not modified, and there is no source module file
            //redeploy content to the web app so that
            //new static web content, JSP jars, etc are hot-swapped
            if(!explodedModule.getSourceModuleFile().exists())
            {
                try
                {
                    explodedModule.deployToWebApp(_webAppDirectory);
                }
                catch(IOException e)
                {
                    logModuleMessage(explodedModule.getRootDirectory().getName(), previouslyLoggedModules, "Could not hot-swap resources from the module " + explodedModule + ".", e);
                    modified = true;
                }
            }
        }

        return modified;
    }

    /**
     * Extract .module files
     * @param args see usages
     * @throws ConfigException thrown if there is a problem with the configuration
     * @throws IOException thrown if there is a problem extracting the module archives
     */
    public static void main(String... args) throws ConfigException, IOException
    {
        try
        {
            PipelineBootstrapConfig config = new PipelineBootstrapConfig(args, false);

            ModuleExtractor extractor = new ModuleExtractor(config.getWebappDir(), new StdOutLogger());
            extractor.extractModules();
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

        System.err.println("java " + ModuleExtractor.class + " [-" + PipelineBootstrapConfig.WEBAPP_DIR + "=<WEBAPP_DIR>] [-" + PipelineBootstrapConfig.CONFIG_DIR + "=<CONFIG_DIR>]");

        System.exit(1);
    }
}
