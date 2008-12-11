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
import java.net.URL;
import java.net.MalformedURLException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

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
    public static final String WEB_CONTENT_PATH = "web";
    public static final String LIB_PATH = "lib";
    public static final String PAGEFLOW_PATH = "lib/_pageflow";
    public static final String CONFIG_PATH = "config";

    protected static final FilenameFilter _jarFilter = new FilenameFilter(){
        public boolean accept(File dir, String name)
        {
            String lowerName = name.toLowerCase();
            return lowerName.endsWith(".jar") && !lowerName.endsWith("_jsp.jar");
        }
    };

    protected static final FilenameFilter _jspJarFilter = new FilenameFilter(){
        public boolean accept(File dir, String name)
        {
            return name.toLowerCase().endsWith("_jsp.jar");
        }
    };

    protected static final FilenameFilter _springConfigFilter = new FilenameFilter(){
        public boolean accept(File dir, String name)
        {
            return name.toLowerCase().endsWith("context.xml");
        }
    };

    protected static final FileComparator _fileComparator = new FileComparator();

    private File _rootDirectory;
    private Map<File,Long> _watchedFiles = new HashMap<File,Long>();

    public ExplodedModule(File rootDirectory)
    {
        assert rootDirectory.exists() && rootDirectory.isDirectory();
        _rootDirectory = rootDirectory;

        //watch the JAR and spring config XML files
        //if they change, the module has been modified
        //and the web app needs to be restarted
        addWatchFiles(getJarFiles());
        addWatchFiles(getSpringConfigFiles());
    }

    public void addWatchFiles(File[] files)
    {
        for(File file : files)
        {
            _watchedFiles.put(file, new Long(file.lastModified()));
        }
    }

    public File getRootDirectory()
    {
        return _rootDirectory;
    }

    public File[] getJarFiles()
    {
        return getFiles(LIB_PATH, _jarFilter);
    }

    public URL[] getJarFileUrls() throws MalformedURLException
    {
        File[] jarFiles = getJarFiles();
        URL[] urls = new URL[jarFiles.length];
        for(int idx = 0; idx < jarFiles.length; ++idx)
        {
            urls[idx] = jarFiles[idx].toURI().toURL();
        }
        return urls;
    }

    public File[] getSpringConfigFiles()
    {
        return getFiles(CONFIG_PATH, _springConfigFilter);
    }

    public void deployToWebApp(File webAppDirectory) throws IOException
    {
        //files to be deployed:
        // - static web content resources to web app dir
        // - page flow XML files to WEB-INF/classes/_pageflow
        // - JSP jar files to WEB-INF/jsp
        // - Spring config XML files to WEB-INF
        File webInfDir = new File(webAppDirectory, "WEB-INF");
        File jspJarDir = new File(webInfDir, "jsp");
        File pageFlowDir = new File(webInfDir, "classes/_pageflow");

        copyBranch(new File(getRootDirectory(), WEB_CONTENT_PATH), webAppDirectory);
        copyFiles(getFiles(PAGEFLOW_PATH, null), pageFlowDir);
        copyFiles(getFiles(LIB_PATH, _jspJarFilter), jspJarDir);
        copyFiles(getFiles(CONFIG_PATH, _springConfigFilter), webInfDir);
    }

    protected File[] getFiles(String relativeDir, FilenameFilter filter)
    {
        File dir = new File(getRootDirectory(), relativeDir);
        if(dir.exists() && dir.isDirectory())
            return null != filter ? dir.listFiles(filter) : dir.listFiles();
        else
            return new File[]{};
    }

    public static void copyFiles(File[] files, File targetDir) throws IOException
    {
        ensureDirectory(targetDir);
        for(File file: files)
            copyFile(file, new File(targetDir, file.getName()));
    }

    public static void copyBranch(File rootDir, File targetDir) throws IOException
    {
        if(!rootDir.exists())
            return;

        for(File file : rootDir.listFiles())
        {
            File destFile = new File(targetDir, file.getName());
            if(file.isDirectory())
            {
                ensureDirectory(destFile);
                copyBranch(file, destFile);
            }
            else
                copyFile(file, destFile);
        }
    }

    public static void ensureDirectory(File dir) throws IOException
    {
        if(!dir.exists())
            dir.mkdirs();
        if(!dir.isDirectory())
            throw new IOException("Unable to create the directory " + dir.getPath() + "! Ensure that the current system user for the web application has sufficient permissions.");
    }

    //NOTE: this was copied from FileUtil since the boostrap module doesn't share any code with the web app
    //consider moving this to some sort of shared JAR
    //incidentally, why in the world is this not in the core Java packages?
    public static void copyFile(File src, File dst) throws IOException
    {
        if(0 == _fileComparator.compare(src, dst))
            return;

        dst.createNewFile();
        FileInputStream is = null;
        FileOutputStream os = null;
        FileChannel in = null;
        FileLock lockIn = null;
        FileChannel out = null;
        FileLock lockOut = null;
        try
        {
            is = new FileInputStream(src);
            in = is.getChannel();
            lockIn = in.lock(0L, Long.MAX_VALUE, true);
            os = new FileOutputStream(dst);
            out = os.getChannel();
            lockOut = out.lock();
            in.transferTo(0, in.size(), out);
            os.getFD().sync();
        }
        finally
        {
            if (null != lockIn)
                lockIn.release();
            if (null != lockOut)
                lockOut.release();
            if (null != in)
                in.close();
            if (null != out)
                out.close();
            if (null != os)
                os.close();
            if (null != is)
                is.close();
        }

        dst.setLastModified(src.lastModified());
    }

    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ExplodedModule that = (ExplodedModule) o;

        if (!_rootDirectory.equals(that._rootDirectory)) return false;

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
        for(Map.Entry<File,Long> entry : _watchedFiles.entrySet())
        {
            if(0 != _fileComparator.compareTimes(entry.getKey().lastModified(), entry.getValue().longValue()))
                return true;
        }
        return false;
    }
}