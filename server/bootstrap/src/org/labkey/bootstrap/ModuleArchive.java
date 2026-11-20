/*
 * Copyright (c) 2008-2026 LabKey Corporation
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

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

/**
 * Represents a compressed module archive file. Use this to explode the module
 * archive into a directory.
 */
public class ModuleArchive
{
    public static final String FILE_EXTENSION = ".module";

    protected static final FileComparator _fileComparator = new FileComparator();

    // Measured on macOS, creating one file costs about as much as writing this many bytes
    private static final long BYTES_PER_FILE_CREATED = 175_000;

    private final File _file;
    private final String _moduleName;
    private final SimpleLogger _log;
    private final long _extractionCost;

    private String stripToNull(String s)
    {
        if (null==s) return null;
        s = s.strip();
        if (s.isEmpty()) return null;
        return s;
    }


    private String nameFromModuleXML(InputStream is) throws IOException
    {
        final AtomicReference<String> moduleName = new AtomicReference<>();

        try
        {
            // Keep this in sync with config on XmlBeansUtil.SAX_PARSER_FACTORY. See motiviations in comments there.
            SAXParserFactory factory = SAXParserFactory.newDefaultInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

            SAXParser parser = factory.newSAXParser();
            parser.parse(is, new DefaultHandler()
            {
                final ArrayList<String> elementStack = new ArrayList<>();

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException
                {
                    String parent = elementStack.isEmpty() ? "" : elementStack.getLast();
                    elementStack.add(qName+"#"+attributes.getValue("id"));
                    if (qName.equals("property") && "bean#moduleBean".equals(parent))
                    {
                        if ("name".equals(attributes.getValue("name")))
                            moduleName.set(attributes.getValue("value"));
                    }
                }

                @Override
                public void endElement(String uri, String localName, String qName) throws SAXException
                {
                    elementStack.removeLast();
                }
            });

            return stripToNull(moduleName.get());
        }
        catch (SAXException|ParserConfigurationException x)
        {
            throw new IOException(x);
        }
    }


    private String nameFromModuleProperties(InputStream is) throws IOException
    {
        Properties props = new Properties();
        props.load(is);
        String ret = null;
        if (props.containsKey("name"))
            ret = props.getProperty("name");
        return stripToNull(ret);
    }


    public ModuleArchive(File file, SimpleLogger log) throws IOException
    {
        _file = file;
        assert _file.exists() && _file.isFile();
        _log = log;

        String moduleName = null;
        long extractionCost = 0;

        /* try to find moduleName in archive */
        try (JarFile jar = new JarFile(getFile()))
        {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements())
            {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                extractionCost += Math.max(0, entry.getSize()) + BYTES_PER_FILE_CREATED;

                if ("config/module.properties".equals(entryName))
                {
                    try (var is = jar.getInputStream(entry))
                    {
                        String name = nameFromModuleProperties(is);
                        if (null != name)
                            moduleName = name.toLowerCase();
                    }
                }

                if ("config/module.xml".equals(entryName))
                {
                    try (var is = jar.getInputStream(entry))
                    {
                        String name = nameFromModuleXML(is);
                        if (null != name)
                            moduleName = name.toLowerCase();
                    }
                }
            }
        }

        _moduleName = moduleName;
        _extractionCost = extractionCost;
    }

    /** Relative cost of a full extraction, for scheduling the biggest archives first */
    public long getExtractionCost()
    {
        return _extractionCost;
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
        if (null == _moduleName)
        {
            String fileName = getFile().getName();
            return fileName.substring(0, fileName.length() - FILE_EXTENSION.length());
        }
        return _moduleName;
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
//        if (_modified != getFile().lastModified())
//            return true;
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
        if (null == targetDirectory)
            throw new IllegalArgumentException("directory parameter was null!");

        // if target exists and is up to date, never mind
        if (!isModified(targetDirectory))
            return;

        File archiveFile = getFile();
        long archiveFileLastModified = archiveFile.lastModified();
        _log.info("Extracting module " + archiveFile.getName() + ".");

        // Rewrite only entries whose content changed; every build rewrites module.xml, so most entries are unchanged
        SequencedSet<File> previouslyExtracted = new LinkedHashSet<>();
        listTree(targetDirectory, previouslyExtracted);
        Set<File> unreplaced = new LinkedHashSet<>();
        List<File> extracted = new ArrayList<>();

        long startTime = System.currentTimeMillis();
        //extract all entries
        try (JarFile jar = new JarFile(archiveFile))
        {
            Enumeration<JarEntry> entries = jar.entries();
            while(entries.hasMoreElements())
            {
                File file = extractEntry(jar, entries.nextElement(), targetDirectory, archiveFileLastModified, unreplaced);
                previouslyExtracted.remove(file);
                extracted.add(file);
            }
        }
        catch (IOException e)
        {
            targetDirectory.setLastModified(0);
            throw new IOException("Failed to process " + archiveFile, e);
        }

        // File.equals() is case-sensitive, so on a case-insensitive file system a case-only rename lists the just-extracted file as stale
        Map<String, File> extractedByFoldedPath = previouslyExtracted.isEmpty() ? Map.of() :
            extracted.stream().collect(Collectors.toMap(ModuleArchive::foldedPath, Function.identity(), (first, _) -> first));

        // Remove what the archive no longer contains; listTree() put children after parents, so delete in reverse
        for (File stale : previouslyExtracted.reversed())
        {
            File current = extractedByFoldedPath.get(foldedPath(stale));
            if (current != null && isSameFile(stale, current))
                continue;
            if (!stale.delete() && stale.isFile())
                unreplaced.add(stale);
        }

        if (!unreplaced.isEmpty())
        {
            // A timestamp no archive has, so the next startup retries the extraction
            targetDirectory.setLastModified(0);
            throw new StaleFilesException("Failed to replace " + unreplaced.size() + " file(s) extracted from an earlier " + archiveFile.getName() +
                ", probably because the running server holds them open. Stop the server and redeploy. Stale files: " + unreplaced);
        }

        //set last mod on target directory to match module file
        targetDirectory.setLastModified(archiveFileLastModified);
        _log.info("Done extracting module " + archiveFile.getName() + ". Processed " + extracted.size() + " file(s) in " + (System.currentTimeMillis() - startTime) + "ms.");
    }


    private static String foldedPath(File file)
    {
        return file.getPath().toLowerCase(Locale.ROOT);
    }


    private static boolean isSameFile(File a, File b)
    {
        try
        {
            return Files.isSameFile(a.toPath(), b.toPath());
        }
        catch (IOException e)
        {
            return false;
        }
    }


    private static void listTree(File dir, Set<File> files)
    {
        File[] children = dir.listFiles();
        if (null == children)
            return;
        for (File child : children)
        {
            files.add(child);
            if (child.isDirectory())
                listTree(child, files);
        }
    }


    public static void ensureChild(File parent, File child) throws IOException
    {
        // Prevent Zip Slip: though we should always only deploy trusted modules, it's wise to always unzip safely
        java.nio.file.Path targetDirPath = parent.toPath().toAbsolutePath().normalize();
        java.nio.file.Path destFilePath = child.toPath().toAbsolutePath().normalize();
        if (!destFilePath.startsWith(targetDirPath))
        {
            throw new IOException("Entry '" + child.getName() + "' is outside of the target directory");
        }
    }


    /**
     * @param lastModified stamped on the extracted file; entry times are fixed across builds, so they can't tell builds apart
     * @param unreplaced files left over from a previous extraction; this entry's file is removed once it's current, or added if it can't be replaced
     */
    public File extractEntry(JarFile jar, JarEntry entry, File targetDirectory, long lastModified, Set<File> unreplaced) throws IOException
    {
        @SuppressWarnings({"SSBasedInspection", "JvmTaintAnalysis"}) File destFile = new File(targetDirectory, entry.getName());
        ensureChild(targetDirectory, destFile);

        // Extraction writes over an earlier build's files, which may have a file where this build has a directory or the reverse
        for (File ancestor = destFile.getParentFile(); ancestor != null && !ancestor.equals(targetDirectory); ancestor = ancestor.getParentFile())
        {
            if (ancestor.isFile())
            {
                if (!ancestor.delete())
                {
                    unreplaced.add(ancestor);
                    return destFile;
                }
                break;
            }
        }
        if (entry.isDirectory() ? destFile.isFile() : destFile.isDirectory())
        {
            List<File> undeleted = new ArrayList<>();
            ExplodedModule.deleteDirectory(destFile, false, undeleted);
            if (!undeleted.isEmpty())
            {
                unreplaced.addAll(undeleted);
                return destFile;
            }
        }

        File entryParent = destFile.getParentFile();
        if (!entryParent.isDirectory())
            entryParent.mkdirs();
        if (!entryParent.isDirectory())
        {
            _log.error("Unable to create directory " + entryParent.getPath() + ", there may be a problem with file permissions");
        }

        // if entry is a directory, just mkdirs, set last mod and return
        if(entry.isDirectory())
        {
            destFile.mkdirs();
            destFile.setLastModified(lastModified);
            return destFile;
        }

        if (!destFile.exists())
        {
            try (InputStream in = jar.getInputStream(entry))
            {
                Files.copy(in, destFile.toPath());
            }
            Files.setLastModifiedTime(destFile.toPath(), FileTime.fromMillis(lastModified));
            return destFile;
        }

        // Unchanged entries keep their file, so a jar the JVM holds open on Windows is only replaced when it changed
        if (hasSameContent(entry, destFile))
        {
            unreplaced.remove(destFile);
            return destFile;
        }

        // Never write over an existing file in place: rewriting a jar the JVM holds open corrupts it for readers sharing the cached zip index
        Path temp = Files.createTempFile(entryParent.toPath(), destFile.getName(), ".tmp");
        try
        {
            try (InputStream in = jar.getInputStream(entry))
            {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.setLastModifiedTime(temp, FileTime.fromMillis(lastModified));

            try
            {
                Files.move(temp, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                unreplaced.remove(destFile);
            }
            catch (IOException e)
            {
                unreplaced.add(destFile);
            }
        }
        finally
        {
            Files.deleteIfExists(temp);
        }

        return destFile;
    }

    /** Entry timestamps are fixed across builds, so only content tells an unchanged file from a changed one */
    private static boolean hasSameContent(JarEntry entry, File file) throws IOException
    {
        if (entry.getCrc() == -1 || entry.getSize() != file.length())
            return false;

        CRC32 crc = new CRC32();
        try (InputStream in = new CheckedInputStream(Files.newInputStream(file.toPath()), crc))
        {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return crc.getValue() == entry.getCrc();
    }

    public File getDefaultExplodedLocation()
    {
        return new File(getFile().getParentFile(), getModuleName());
    }

    /** The message names every stale file, so callers log it without a stack trace */
    public static class StaleFilesException extends IOException
    {
        public StaleFilesException(String message)
        {
            super(message);
        }
    }
}
