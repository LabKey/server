/*
 * Copyright (c) 2008-2011 LabKey Corporation
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/*
* User: Dave
* Date: Dec 8, 2008
* Time: 11:41:00 AM
*/

/**
 * Represents a compressed module archive file. Use this to explode the module
 * archive into a directory.
 */
public class ModuleArchive
{
    public static final String FILE_EXTENSION = ".module";

    protected static final JarEntryComparator _jarEntryComparator = new JarEntryComparator();
    protected static final FileComparator _fileComparator = new FileComparator();

    private File _file;
    private final SimpleLogger _log;

    public ModuleArchive(File file, SimpleLogger log)
    {
        _file = file;
        assert _file.exists() && _file.isFile();
        _log = log;
    }

    public File getFile()
    {
        return _file;
    }

    public long lastModified()
    {
        return getFile().lastModified();
    }

    public String getModuleName()
    {
        String fileName = getFile().getName();
        return fileName.substring(0, fileName.length() - FILE_EXTENSION.length());
    }

    /**
     * Returns true if this module archive is different than its default exploded location
     * @return true if this archive has been modified and needs re-extraction
     */
    public boolean isModified()
    {
        return isModified(getDefaultExplodedLocation());
    }

    public boolean isModified(File targetDirectory)
    {
        return 0 != _fileComparator.compare(getFile(), targetDirectory);
    }

    /**
     * Extracts all entries in the module archive to a peer directory
     * with the same name as the module. For example, /modules/mymodule.module
     * will be extracted to /modules/mymodule/
     * @return The directory into which this module was exploded
     * @throws IOException thrown if there is an error writing files
     */
    public File extractAll() throws IOException
    {
        File targetDir = getDefaultExplodedLocation();
        extractAll(targetDir);
        return targetDir;
    }

    /**
     * Extracts all entries in the module archive to the target directory. If the
     * target directory does not exist, it will be created.
     * @param targetDirectory The target directory
     * @throws IOException thrown if there is an error writing files
     */
    public void extractAll(File targetDirectory) throws IOException
    {
        if(null == targetDirectory)
            throw new IllegalArgumentException("directory parameter was null!");

        // if target exists and is up to date, never mind
        if (!isModified(targetDirectory))
            return;

        File archiveFile = getFile();
        _log.info("Extracting the module " + archiveFile.getName() + ".");

        //delete existing directory so that files that are
        //no longer in the archive are removed
        ExplodedModule.deleteDirectory(targetDirectory);

        //extract all entries
        JarFile jar = null;
        try
        {
            jar = new JarFile(archiveFile);
            Enumeration<JarEntry> entries = jar.entries();
            while(entries.hasMoreElements())
            {
                extractEntry(jar, entries.nextElement(), targetDirectory);
            }
        }
        catch (IOException e)
        {
            throw new IOException("Failed to process " + archiveFile, e);
        }
        finally
        {
            if(null != jar)
                try {jar.close();} catch(IOException ignore){}
        }

        //set last mod on target directory to match module file
        Files.setLastModifiedTime(targetDirectory.toPath(), FileTime.fromMillis(archiveFile.lastModified()));
    }

    public File extractEntry(JarFile jar, JarEntry entry, File targetDirectory) throws IOException
    {
        File destFile = new File(targetDirectory, entry.getName());

        File entryParent = destFile.getParentFile();
        entryParent.mkdirs();
        if (!entryParent.isDirectory())
        {
            _log.error("Unable to create directory " + entryParent.getPath() + ", there may be a problem with file permissions");
        }

        // if entry is a directory, just mkdirs, set last mod and return
        if(entry.isDirectory())
        {
            destFile.mkdir();
            return destFile;
        }

        if (0 != _jarEntryComparator.compare(entry, destFile))
        {
            Path destPath = destFile.toPath();
            Files.copy(jar.getInputStream(entry), destPath, REPLACE_EXISTING);

            if (entry.getTime() != -1)
            {
                Files.setLastModifiedTime(destPath, FileTime.fromMillis(entry.getTime()));
            }
        }

        return destFile;
    }

    public File getDefaultExplodedLocation()
    {
        return new File(getFile().getParentFile(), getModuleName());
    }
}
