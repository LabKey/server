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
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Represents a compressed module archive file. Use this to explode the module
 * archive into a directory.
 */
public class ModuleArchive
{
    public static final String FILE_EXTENSION = ".module";

    protected static final JarEntryComparator _jarEntryComparator = new JarEntryComparator();
    protected static final FileComparator _fileComparator = new FileComparator();

    private final File _file;
    private final String _moduleName;
    private final SimpleLogger _log;

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

        /* try to find moduleName in archive */
        try (JarFile jar = new JarFile(getFile()))
        {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements())
            {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

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

        // delete existing directory so that files that are
        // no longer in the archive are removed
        ExplodedModule.deleteDirectory(targetDirectory, false);

        long startTime = System.currentTimeMillis();
        int fileCount = 0;
        //extract all entries
        try (JarFile jar = new JarFile(archiveFile))
        {
            Enumeration<JarEntry> entries = jar.entries();
            while(entries.hasMoreElements())
            {
                extractEntry(jar, entries.nextElement(), targetDirectory);
                fileCount++;
            }
        }
        catch (IOException e)
        {
            throw new IOException("Failed to process " + archiveFile, e);
        }

        //set last mod on target directory to match module file
        targetDirectory.setLastModified(archiveFileLastModified);
        _log.info("Done extracting module " + archiveFile.getName() + ". Processed " + fileCount + " file(s) in " + (System.currentTimeMillis() - startTime) + "ms.");
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


    public File extractEntry(JarFile jar, JarEntry entry, File targetDirectory) throws IOException
    {
        @SuppressWarnings({"SSBasedInspection", "JvmTaintAnalysis"}) File destFile = new File(targetDirectory, entry.getName());
        ensureChild(targetDirectory, destFile);

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
            if (entry.getTime() != -1)
                destFile.setLastModified(entry.getTime());
            return destFile;
        }

        if (0 != _jarEntryComparator.compare(entry, destFile))
        {
            try (BufferedInputStream bIn = new BufferedInputStream(jar.getInputStream(entry)); BufferedOutputStream bOut = new BufferedOutputStream(new FileOutputStream(destFile)))
            {
                byte[] b = new byte[8192];
                int i;
                while ((i = bIn.read(b)) != -1)
                {
                    bOut.write(b, 0, i);
                }
            }

            if (entry.getTime() != -1)
            {
                destFile.setLastModified(entry.getTime());
            }
        }

        return destFile;
    }

    public File getDefaultExplodedLocation()
    {
        return new File(getFile().getParentFile(), getModuleName());
    }
}
