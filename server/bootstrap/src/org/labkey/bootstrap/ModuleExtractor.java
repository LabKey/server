/*
 * Copyright (c) 2008-2018 LabKey Corporation
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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        _moduleArchiveFiles = getConcurrentSet();
        _errorArchives = new ConcurrentHashMap<>();
        _ignoredExplodedDirs = getConcurrentSet();

        _log.info("Exploding module archives");

        // Explode each module archive file into its directory, in parallel. Note: Default thread pool uses (CPU - 1) threads.

        // It might be tempting to try replacing .collect().parallelStream() below with .parallel(), but the intermediate
        // list is critical in this case. File.listFiles() can't estimate the size of its results, so invoking parallel()
        // directly leads to a terrible splitting strategy that has no parallelization benefit.
        // https://stackoverflow.com/questions/34341656/why-is-files-list-parallel-stream-performing-so-much-slower-than-using-collect
        _moduleDirectories.streamAllModuleDirectories()
            .flatMap(dir->Stream.of(dir.listFiles(moduleArchiveFilter)))
            .collect(Collectors.toList()) // This intermediate list is critical. See comment above.
            .parallelStream()
            .forEach(moduleArchiveFile->{
                try
                {
                    ModuleArchive moduleArchive = new ModuleArchive(moduleArchiveFile, _log);
                    moduleArchive.extractAll();
                    _moduleArchiveFiles.add(moduleArchiveFile);
                }
                catch (IOException e)
                {
                    _log.error("Unable to extract module archive " + moduleArchiveFile.getPath() + "!", e);
                    _errorArchives.put(moduleArchiveFile, moduleArchiveFile.lastModified());
                }
            });

        _log.info("Deploying resources from exploded modules to web app directory");
        _explodedModules = getConcurrentSet();

        // Deploy resources from modules, in parallel. Note: Default thread pool uses (CPU - 1) threads.
        // This must be a separate step from module extraction (above) to support module directories that don't come
        // from a .module archive.
        _moduleDirectories.streamAllModuleDirectories()
            .flatMap(dir->Stream.of(dir.listFiles(File::isDirectory)))
            .collect(Collectors.toList()) // This intermediate list is critical. See comment above.
            .parallelStream()
            .forEach(dir->{
                if (dir.isHidden())
                {
                    _ignoredExplodedDirs.add(dir);
                    return;
                }

                try
                {
                    ExplodedModule explodedModule = new ExplodedModule(dir);
                    _log.info("Deploying resources from " + explodedModule.getRootDirectory() + ".");
                    Set<File> moduleWebAppFiles = explodedModule.deployToWebApp(_webAppDirectory);
                    if (null != webAppFiles)
                        webAppFiles.addAll(moduleWebAppFiles);

                    _explodedModules.add(explodedModule);
                    _log.info("Done deploying resources from " + explodedModule.getRootDirectory() + ".");
                }
                catch(IOException e)
                {
                    _log.error("Unable to deploy resources from exploded module " + dir.getPath() + " to web app directory!", e);
                }
            });

        _log.info("Module extraction and deployment complete.");
        if (null != webAppFiles)
            cleanupWebAppDir(webAppFiles);

        return _explodedModules;
    }

    private <E> Set<E> getConcurrentSet()
    {
        return Collections.newSetFromMap(new ConcurrentHashMap<>());
    }

    private <E> Set<E> getConcurrentSet(Set<E> set)
    {
        Set<E> ret = getConcurrentSet();
        ret.addAll(set);

        return ret;
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

    /**
     *  @return null if there was a problem (likely file system permissions) that prevents us from reading the directory,
     *  otherwise return a ConcurrentSet of all the webapp files.     *
     **/
    protected Set<File> getWebAppFiles()
    {
        //load the apiFiles.list to get a list of all files that are part of the core web app
        File apiFiles = new File(_webAppDirectory, DirectoryFileListWriter.API_FILES_LIST_RELATIVE_PATH);
        if (!apiFiles.exists())
        {
            _log.info("WARNING: could not find list of web app files at " + apiFiles.getPath() + ". Automatic cleanup of web app directory will not occur.");
            return null;
        }

        //file contains one path per line
        Set<File> files = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(apiFiles), StandardCharsets.UTF_8)))
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

        return getConcurrentSet(files);
    }


    public List<File> getExplodedModuleDirectories()
    {
        List<File> dirs = new ArrayList<>();
        for(ExplodedModule expMod : _explodedModules)
        {
            dirs.add(expMod.getRootDirectory());
        }
        dirs.sort(Comparator.comparing(File::getName));
        return dirs;
    }

    private void logModuleMessage(String module, Set<String> previouslyLoggedModules, String message)
    {
        logModuleMessage(module, previouslyLoggedModules, message, null);
    }

    /** Logs the message if we haven't previously logged a message about that module */
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
                    logModuleMessage(explodedModule.getRootDirectory().getName(), previouslyLoggedModules, "Could not hot-swap resources from module " + explodedModule + ".", e);
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
    public static void main(String... args)
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
