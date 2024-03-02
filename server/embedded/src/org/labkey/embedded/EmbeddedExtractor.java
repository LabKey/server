package org.labkey.embedded;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.core.util.VersionUtil;
import org.labkey.bootstrap.ConfigException;
import org.springframework.util.StreamUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class EmbeddedExtractor
{
    private static final int BUFFER_SIZE = 1024 * 64;

    private final File currentDir = new File("").getAbsoluteFile();
    private final File labkeyServerJar;

    public EmbeddedExtractor()
    {
        File[] files = currentDir.listFiles(file -> {
            String name = file.getName().toLowerCase();
            return name.endsWith(".jar") && name.contains("labkeyserver");
        });

        if (files == null || files.length == 0)
        {
            labkeyServerJar = null;
        }
        else if (files.length > 1)
        {
            throw new ConfigException("Multiple jars found - " + Arrays.asList(files) + ". Must provide only one jar.");
        }
        else
        {
            labkeyServerJar = files[0];
        }
    }

    public File getLabkeyServerJar()
    {
        return labkeyServerJar;
    }

    public boolean shouldUpgrade(File webAppLocation)
    {
        File existingVersionFile = new File(webAppLocation, "WEB-INF/classes/VERSION");

        // Upgrade from standalone Tomcat installation
        if (!existingVersionFile.exists())
            return true;

        String existingVersion;
        try
        {
            existingVersion = Files.readString(existingVersionFile.toPath()).trim();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }

        String newVersion = getNewVersion();

        Version v1 = getLabKeyVersion(existingVersion);
        Version v2 = getLabKeyVersion(newVersion);

        return v1.compareTo(v2) > 0;
    }

    private String getNewVersion()
    {
        verifyJar();

        try
        {
            try (JarFile jar = new JarFile(labkeyServerJar))
            {
                var entries = jar.entries();
                while (entries.hasMoreElements())
                {
                    var entry = entries.nextElement();
                    var entryName = entry.getName();

                    if ("labkey/distribution.zip".equals(entryName))
                    {
                        try (ZipInputStream zipIn = new ZipInputStream(jar.getInputStream(entry)))
                        {
                            ZipEntry zipEntry = zipIn.getNextEntry();
                            // iterates over entries in the zip file
                            while (zipEntry != null)
                            {
                                if (!zipEntry.isDirectory() && zipEntry.getName().equals("labkeywebapp/WEB-INF/classes/VERSION"))
                                {
                                    return StreamUtils.copyToString(zipIn, Charset.defaultCharset());
                                }
                                zipIn.closeEntry();
                                zipEntry = zipIn.getNextEntry();
                            }
                        }
                        throw new ConfigException("Unable to determine version of distribution.");
                    }
                }

                throw new ConfigException("Unable to find distribution zip required to run LabKey Server.");
            }
        }
        catch (IOException | ConfigException e)
        {
            throw new RuntimeException(e);
        }
    }

    private void verifyJar()
    {
        if (labkeyServerJar == null)
        {
            throw new ConfigException("Executable jar not found in " + currentDir);
        }
    }

    public void extractExecutableJar(File destDirectory, boolean remotePipeline)
    {
        verifyJar();

        backupExistingDeployment(destDirectory);

        try
        {
            try (JarFile jar = new JarFile(labkeyServerJar))
            {
                boolean foundDistributionZip = false;
                var entries = jar.entries();
                while (entries.hasMoreElements())
                {
                    var entry = entries.nextElement();
                    var entryName = entry.getName();

                    if ("labkey/distribution.zip".equals(entryName))
                    {
                        foundDistributionZip = true;
                        try (var distInputStream = jar.getInputStream(entry))
                        {
                            extractZip(distInputStream, destDirectory);
                        }
                    }
                    if (remotePipeline)
                    {
                        if (entry.getName().contains("labkeyBootstrap") && entry.getName().toLowerCase().endsWith(".jar"))
                        {
                            try (var in = jar.getInputStream(entry))
                            {
                                extractFile(in, new File(destDirectory, "labkeyBootstrap.jar"));
                            }
                        }
                        if (entry.getName().contains("tomcat-servlet-api") && entry.getName().toLowerCase().endsWith(".jar"))
                        {
                            File pipelineLib = new File(destDirectory, "pipeline-lib");
                            if (!pipelineLib.exists())
                            {
                                if (!pipelineLib.mkdirs())
                                {
                                    throw new ConfigException("Failed to create directory " + pipelineLib + " Please check file system permissions");
                                }
                            }
                            try (var in = jar.getInputStream(entry))
                            {
                                extractFile(in, new File(pipelineLib, "servletApi.jar"));
                            }
                        }
                    }
                }

                if (!foundDistributionZip)
                {
                    throw new ConfigException("Unable to find distribution zip required to run LabKey Server.");
                }
            }
        }
        catch (IOException | ConfigException e)
        {
            throw new RuntimeException(e);
        }
    }

    private void extractZip(InputStream zipInputStream, File destDir) throws IOException
    {
        //noinspection SSBasedInspection
        if (!destDir.exists() && !destDir.mkdirs())
        {
            throw new IOException("Failed to create directory " + destDir + " - please check file system permissions");
        }
        try (ZipInputStream zipIn = new ZipInputStream(zipInputStream))
        {
            ZipEntry entry = zipIn.getNextEntry();
            // iterates over entries in the zip file
            while (entry != null)
            {
                File filePath = new File(destDir, entry.getName());
                if (!entry.isDirectory())
                {
                    // if the entry is a file, extracts it
                    extractFile(zipIn, filePath);
                }
                else
                {
                    // if the entry is a directory, make the directory
                    //noinspection SSBasedInspection
                    if (!filePath.exists() && !filePath.mkdirs())
                    {
                        throw new IOException("Failed to create directory " + filePath + " - please check file system permissions");
                    }
                }
                zipIn.closeEntry();
                entry = zipIn.getNextEntry();
            }
        }
    }

    private static void extractFile(InputStream zipIn, File filePath) throws IOException
    {
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath)))
        {
            byte[] bytesIn = new byte[BUFFER_SIZE];
            int read;
            while ((read = zipIn.read(bytesIn)) != -1)
            {
                bos.write(bytesIn, 0, read);
            }
        }
    }

    //TODO: backup or delete existing files
    private void backupExistingDeployment(File deployDir)
    {
        File webappDir = new File(deployDir, "labkeywebapp");
    }

    private Version getLabKeyVersion(String versionString)
    {
        Version v = VersionUtil.parseVersion(versionString, null, null);
        if (versionString.endsWith("-SNAPSHOT")) // `v.isSnapshot()` doesn't work
        {
            // SNAPSHOTs should be assumed to be newer than non-SNAPSHOTs of the same version
            v = new Version(v.getMajorVersion(), v.getMinorVersion(), Integer.MAX_VALUE, "SNAPSHOT", null, null);
        }
        return v;
    }
}
