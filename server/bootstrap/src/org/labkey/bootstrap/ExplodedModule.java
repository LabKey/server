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
import java.util.*;
import java.net.URL;
import java.net.MalformedURLException;
import java.nio.channels.FileChannel;

/*
* User: Dave
* Date: Dec 8, 2008
* Time: 3:08:00 PM
*/

/**
 * Represents an exploded module directory.
 * This class makes assumptions about the layout of the module directories,
 * but these assumptions will, for the most part, be isolated here.
 */
public class ExplodedModule
{
    private static final String WEB_CONTENT_PATH = "web";
    private static final String LIB_PATH = "lib";
    private static final String CONFIG_PATH = "config";

    // With Gradle 1.8, we removed the -jsp classifier at the end of the jar file name, so we need to identify by the string _jsp- in the middle of the jar file name (e.g., announcements_jsp-19.3-SNAPSHOT.jar)
    private static final FilenameFilter _jspJarFilter = (dir, name) -> name.toLowerCase().contains("_jsp-");
    private static final FilenameFilter _springConfigFilter = (dir, name) -> name.toLowerCase().endsWith("context.xml");
    private static final FilenameFilter _moduleXmlFilter = (dir, name) -> name.toLowerCase().equals("module.xml");
    private static final FilenameFilter _gwtFilter = (dir, name) -> name.endsWith(".gwt.rpc");

    private static final FilenameFilter _jarFilter = (dir, name) -> {
        String lowerName = name.toLowerCase();
        return lowerName.endsWith(".jar") && !_jspJarFilter.accept(dir, name);
    };

    private static final FileComparator _fileComparator = new FileComparator();

    private File _rootDirectory;
    private File _sourceModuleFile;
    private Map<File, Long> _watchedFiles = new HashMap<>();

    public ExplodedModule(File rootDirectory)
    {
        this(rootDirectory, null);
    }

    public ExplodedModule(File rootDirectory, File sourceModuleFile)
    {
        assert rootDirectory.exists() && rootDirectory.isDirectory();
        _rootDirectory = rootDirectory;
        _sourceModuleFile = sourceModuleFile;

        //watch the module & spring config XML files, plus JAR files
        //if they change, the module has been modified
        //and the web app needs to be restarted
        addWatchFiles(getJarFiles());
        addWatchFiles(getSpringConfigFiles());
        addWatchFiles(getModuleXmlFiles());
    }

    public void addWatchFiles(Collection<File> files)
    {
        for (File file : files)
        {
            _watchedFiles.put(file, file.lastModified());
        }
    }

    public File getRootDirectory()
    {
        return _rootDirectory;
    }

    public List<File> getJarFiles()
    {
        return getFiles(LIB_PATH, _jarFilter);
    }

    public List<File> getModuleXmlFiles()
    {
        return getFiles(CONFIG_PATH, _moduleXmlFilter);
    }

    public URL[] getJarFileUrls() throws MalformedURLException
    {
        List<File> jarFiles = getJarFiles();
        URL[] urls = new URL[jarFiles.size()];
        for (int idx = 0; idx < jarFiles.size(); ++idx)
        {
            urls[idx] = jarFiles.get(idx).toURI().toURL();
        }
        return urls;
    }

    public List<File> getSpringConfigFiles()
    {
        List<File> result = new ArrayList<>();
        result.addAll(getFiles(CONFIG_PATH, _springConfigFilter));
        result.addAll(getFiles("web/WEB-INF", _springConfigFilter));
        return result;
    }

    public Set<File> deployToWebApp(File webAppDirectory) throws IOException
    {
        //files to be deployed:
        // - JSP jar files to WEB-INF/jsp
        // - Spring config XML files to WEB-INF

        File webInfDir = new File(webAppDirectory, "WEB-INF");
        Set<File> webAppFiles = new HashSet<>();

        copyBranch(new File(getRootDirectory(), WEB_CONTENT_PATH + "/WEB-INF"), new File(webAppDirectory, "WEB-INF"), webAppFiles);
        // GWTServlet depends on finding its gwt.rpc artifacts in the webapp
        copyBranch(new File(getRootDirectory(), WEB_CONTENT_PATH), webAppDirectory, webAppFiles, _gwtFilter);

        copyFiles(getFiles(CONFIG_PATH, _springConfigFilter), webInfDir, webAppFiles);

        return webAppFiles;
    }

    protected List<File> getFiles(String relativeDir, FilenameFilter filter)
    {
        File dir = new File(getRootDirectory(), relativeDir);
        if (dir.exists() && dir.isDirectory())
        {
            var list = null != filter ? dir.listFiles(filter) : dir.listFiles();
            return null == list ? Collections.emptyList() : Arrays.asList(list);
        }
        else
            return Collections.emptyList();
    }

    public static void copyFiles(Collection<File> files, File targetDir, Set<File> filesCopied) throws IOException
    {
        ensureDirectory(targetDir);
        if (null != filesCopied)
            filesCopied.add(targetDir);

        for (File file : files)
        {
            File dest = new File(targetDir, file.getName());
            copyFile(file, dest);
            if (null != filesCopied)
                filesCopied.add(dest);
        }
    }

    public static void copyBranch(File rootDir, File targetDir, Set<File> filesCopied) throws IOException
    {
        copyBranch(rootDir, targetDir, filesCopied, null);
    }


    public static void copyBranch(File rootDir, File targetDir, Set<File> filesCopied, FilenameFilter filter) throws IOException
    {
        if (!rootDir.exists())
            return;

        var list = rootDir.listFiles();
        if (null == list)
            return;

        boolean ensuredTargetIsDirectory = false;

        for (File file : list)
        {
            File destFile = new File(targetDir, file.getName());

            if (file.isDirectory())
            {
                // don't actually create target dir until we add at least one file
                if (null != filesCopied)
                    filesCopied.add(targetDir);
                copyBranch(file, destFile, filesCopied, filter);
            }
            else if (null == filter || filter.accept(rootDir, file.getName()))
            {
                if (!ensuredTargetIsDirectory)
                    ensureDirectory(targetDir);
                ensuredTargetIsDirectory = true;
                if (null != filesCopied)
                    filesCopied.add(destFile);
                copyFile(file, destFile);
            }
        }
    }

    public static void ensureDirectory(File dir) throws IOException
    {
        ensureDirectory(dir, false);
    }

    public static void ensureDirectory(File dir, boolean deleteExisting) throws IOException
    {
        if (dir.exists() && deleteExisting)
            deleteDirectory(dir);

        if (!dir.exists())
            dir.mkdirs();
        if (!dir.isDirectory())
            throw new IOException("Unable to create the directory " + dir.getPath() + "! Ensure that the current system user for the web application has sufficient permissions.");
    }

    public static void deleteDirectory(File dir)
    {
        //can't delete a directory unless everything inside it is deleted
        if (dir.isDirectory())
        {
            var list = dir.listFiles();
            if (null != list)
            {
                for (File child : list)
                {
                    if (child.isDirectory())
                        deleteDirectory(child);
                    else
                        child.delete();
                }
            }
        }

        dir.delete();
    }

    //NOTE: this was copied from FileUtil since the bootstrap module doesn't share any code with the web app
    //consider moving this to some sort of shared JAR
    //incidentally, why in the world is this not in the core Java packages?
    public static void copyFile(File src, File dst) throws IOException
    {
        if (0 == _fileComparator.compare(src, dst))
            return;

        dst.createNewFile();
        try (FileChannel sourceChannel = new FileInputStream(src).getChannel();
             FileChannel destChannel = new FileOutputStream(dst).getChannel())
        {
            destChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
        }

        dst.setLastModified(src.lastModified());
    }

    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        ExplodedModule that = (ExplodedModule) o;

        if (!_rootDirectory.equals(that._rootDirectory))
            return false;

        return true;
    }

    public int hashCode()
    {
        return _rootDirectory.hashCode();
    }

    public String toString()
    {
        return _rootDirectory.toString();
    }

    public boolean isModified()
    {
        for (Map.Entry<File, Long> entry : _watchedFiles.entrySet())
        {
            if (0 != _fileComparator.compareTimes(entry.getKey().lastModified(), entry.getValue()))
                return true;
        }
        return false;
    }

    public void setSourceModuleFile(File file)
    {
        _sourceModuleFile = file;
    }

    public File getSourceModuleFile()
    {
        return _sourceModuleFile;
    }
}