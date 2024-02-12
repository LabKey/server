package org.labkey.embedded;

import org.labkey.bootstrap.ConfigException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class EmbeddedExtractor
{
    private static final int BUFFER_SIZE = 1024 * 64;

    private void extractExecutableJar(File jarFilePath, File destDirectory, boolean remotePipeline)
    {
        try
        {
            try (JarFile jar = new JarFile(jarFilePath))
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

//                if (!foundDistributionZip)
//                {
//                    throw new ConfigException("Unable to find distribution zip required to run LabKey Server.");
//                }
            }
        }
        catch (IOException | ConfigException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void extractExecutableJarFromDir(File currentDir, File destDir, boolean remotePipeline) throws ConfigException
    {
        File[] files = currentDir.listFiles(file -> {
            String name = file.getName().toLowerCase();
            return name.endsWith(".jar") && !name.contains("labkeybootstrap");
        });

        if (files == null)
        {
            throw new ConfigException("Executable jar not found.");
        }

        // only 1 jar should be there
        if (files.length == 1)
        {
            extractExecutableJar(files[0], destDir, remotePipeline);
        }
        else
        {
            throw new ConfigException("Multiple jars found - " + Arrays.asList(files) + ". Must provide only one jar.");
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

}
