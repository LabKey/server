/*
 * Copyright (c) 2024-2026 LabKey Corporation
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
package org.labkey.embedded;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.labkey.bootstrap.ConfigException;
import org.labkey.bootstrap.ModuleArchive;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@SuppressWarnings("SSBasedInspection") // Disable warnings intended for webapp code
public class EmbeddedExtractor
{
    private static final Logger LOG = LogManager.getLogger(EmbeddedExtractor.class);
    private static final int BUFFER_SIZE = 1024 * 64;
    public static final String LABKEYWEBAPP = "labkeywebapp";
    /**
     * Directories that are expected to exist in 'distribution.zip'.
     */
    private static final Set<String> EXPECTED_DIST_DIRS = Set.of(LABKEYWEBAPP, "modules");

    private final File currentDir = new File("").getAbsoluteFile();
    private final File labkeyServerJar;

    private String labkeyWebappDirName = null;

    public EmbeddedExtractor(boolean allowEmbedded)
    {
        File[] files = currentDir.listFiles(file -> {
            String name = file.getName().toLowerCase();
            return name.endsWith(".jar") && !name.contains("labkeybootstrap") && (allowEmbedded || !name.contains("embedded"));
        });

        if (files == null || files.length == 0)
        {
            labkeyServerJar = null;
            LOG.debug("Executable jar not found in {}", currentDir);
        }
        else if (files.length > 1)
        {
            throw new ConfigException("Multiple jars found - " + Arrays.asList(files) + ". Must provide only one jar.");
        }
        else
        {
            labkeyServerJar = files[0];
            LOG.debug("Executable jar found: {}", labkeyServerJar.getAbsolutePath());
        }
    }

    public boolean foundLabkeyServerJar()
    {
        return labkeyServerJar != null;
    }

    private File verifyJar()
    {
        if (labkeyServerJar == null)
        {
            throw new ConfigException("Executable jar not found in " + currentDir + " which had contents " + Arrays.stream(currentDir.listFiles()).map(File::getName).toList());
        }

        return labkeyServerJar;
    }

    private boolean shouldExtract(File webAppLocation)
    {
        File existingDistributionFile = new File(webAppLocation, "WEB-INF/classes/distribution.properties");

        LabKeyDistributionInfo incomingDistribution = getDistributionInfo();

        // Fresh installation or upgrading from a pre-distribution.properties distribution
        if (!existingDistributionFile.exists())
        {
            LOG.info("Extracting new LabKey distribution - {}", incomingDistribution);
            return true;
        }

        LabKeyDistributionInfo existingDistribution;

        try
        {
            try (InputStream is = Files.newInputStream(existingDistributionFile.toPath()))
            {
                existingDistribution = getFromProperties(is);
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }

        if (!existingDistribution.equals(incomingDistribution))
        {
            LOG.info("Updating LabKey ({} -> {}})", existingDistribution, incomingDistribution);
            return true;
        }
        else if (incomingDistribution.buildUrl() == null)
        {
            LOG.info("Extracting custom-build LabKey distribution ({})", existingDistribution);
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Extract distribution info from bundled distribution.zip.
     * Also verifies that distribution.zip contains expected files
     * @return An object describing the distribution
     */
    private LabKeyDistributionInfo getDistributionInfo()
    {
        LabKeyDistributionInfo info = null;

        try
        {
            try (JarFile jar = new JarFile(verifyJar()))
            {
                var entries = jar.entries();
                while (entries.hasMoreElements())
                {
                    var entry = entries.nextElement();
                    var entryName = entry.getName();

                    if ("labkey/distribution.zip".equals(entryName))
                    {
                        Set<String> distributionDirs = new HashSet<>();
                        try (ZipInputStream zipIn = new ZipInputStream(jar.getInputStream(entry)))
                        {
                            ZipEntry zipEntry = zipIn.getNextEntry();
                            // iterates over entries in the zip file
                            while (zipEntry != null)
                            {
                                distributionDirs.add(zipEntry.getName().split("/", 2)[0]);
                                if (!zipEntry.isDirectory() && zipEntry.getName().equals(LABKEYWEBAPP + "/WEB-INF/classes/distribution.properties"))
                                {
                                    info = getFromProperties(zipIn);
                                }
                                zipIn.closeEntry();
                                zipEntry = zipIn.getNextEntry();
                            }
                        }

                        if (!distributionDirs.equals(EXPECTED_DIST_DIRS))
                        {
                            StringBuilder msg = new StringBuilder("Corrupted distribution; contents are not as expected.");

                            Set<String> missingDirs = EXPECTED_DIST_DIRS.stream().filter(d -> !distributionDirs.contains(d)).collect(Collectors.toSet());
                            if (!missingDirs.isEmpty())
                            {
                                msg.append(" Missing directories: ");
                                msg.append(missingDirs);
                                msg.append(".");
                            }

                            Set<String> extraDirs = distributionDirs.stream().filter(d -> !EXPECTED_DIST_DIRS.contains(d)).collect(Collectors.toSet());
                            if (!extraDirs.isEmpty())
                            {
                                msg.append(" Unexpected directories: ");
                                msg.append(extraDirs);
                                msg.append(".");
                            }

                            throw new IllegalStateException(msg.toString());
                        }

                        if (null == info)
                            throw new IllegalStateException("distribution.properties file was not found!");

                        return info;
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

    // Caller must close the stream
    private LabKeyDistributionInfo getFromProperties(InputStream in) throws IOException
    {
        Properties props = new Properties();
        props.load(in);
        String distributionName = props.getProperty("name", "").trim();
        String version = props.getProperty("version", "").trim();
        String buildUrl = props.containsKey("buildUrl") ? props.getProperty("buildUrl").trim() : null;

        var info = new LabKeyDistributionInfo(version, buildUrl, distributionName);
        LOG.info("LabKeyDistributionInfo: {}", info);

        return info;
    }

    public void extractDistribution(File webAppLocation)
    {
        if (shouldExtract(webAppLocation))
        {
            labkeyWebappDirName = webAppLocation.getName();
            deleteOldDistribution(webAppLocation);
            extractExecutableJar(webAppLocation.getParentFile(), false);
        }
    }

    @SuppressWarnings("unused") /* Called via reflection by PipelineServiceImpl.getClusterStartupArguments() */
    public File extractRemotePipelineJars()
    {
        return extractExecutableJar(new File("."), false, true);
    }

    public void extractExecutableJar(File destDirectory, boolean remotePipeline)
    {
        extractExecutableJar(destDirectory, true, remotePipeline);
    }

    public File extractExecutableJar(File destDirectory, boolean distribution, boolean remotePipeline)
    {
        File pipelineLib = null;
        if (remotePipeline)
        {
            pipelineLib = new File(destDirectory, "pipeline-lib");
            if (!pipelineLib.exists())
            {
                if (!pipelineLib.mkdirs())
                {
                    throw new ConfigException("Failed to create directory " + pipelineLib + " Please check file system permissions");
                }
            }
        }

        boolean foundDistributionZip = false;
        File bootstrapJar = null;
        File servletApiJar = null;
        File log4JCoreJar = null;
        File log4JApiJar = null;

        try
        {
            try (JarFile jar = new JarFile(verifyJar()))
            {
                var entries = jar.entries();
                while (entries.hasMoreElements())
                {
                    var entry = entries.nextElement();
                    var entryName = entry.getName();

                    if (distribution)
                    {
                        if ("labkey/distribution.zip".equals(entryName))
                        {
                            foundDistributionZip = true;
                            try (var distInputStream = jar.getInputStream(entry))
                            {
                                extractDistributionZip(distInputStream, destDirectory);
                            }
                        }
                    }
                    if (remotePipeline)
                    {
                        // Keep this code in sync with org.labkey.pipeline.api.PipelineServiceImpl.getClusterStartupArguments()
                        bootstrapJar = extractIfMatch(bootstrapJar, entry, jar, "labkeyBootstrap", "labkeyBootstrap.jar", destDirectory);
                        servletApiJar = extractIfMatch(servletApiJar, entry, jar, "tomcat-embed-core", "servletApi.jar", pipelineLib);
                        log4JCoreJar = extractIfMatch(log4JCoreJar, entry, jar, "log4j-core", "log4j-core.jar", pipelineLib);
                        log4JApiJar = extractIfMatch(log4JApiJar, entry, jar, "log4j-api", "log4j-api.jar", pipelineLib);
                    }
                }

                if (distribution)
                {
                    if (!foundDistributionZip)
                    {
                        throw new ConfigException("Unable to find distribution zip required to run LabKey Server.");
                    }
                }

                if (remotePipeline)
                {
                    if (bootstrapJar == null)
                    {
                        throw new ConfigException("Unable to find labkeyServer.jar required to run LabKey Server's remote pipeline code.");
                    }
                    if (servletApiJar == null)
                    {
                        throw new ConfigException("Unable to find Servlet API file required to run LabKey Server's remote pipeline code.");
                    }
                    if (log4JCoreJar == null)
                    {
                        throw new ConfigException("Unable to find Log4J Core file required to run LabKey Server's remote pipeline code.");
                    }
                    if (log4JApiJar == null)
                    {
                        throw new ConfigException("Unable to find Log4J API file required to run LabKey Server's remote pipeline code.");
                    }
                }
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        return bootstrapJar;
    }

    private File extractIfMatch(File extractedFile, JarEntry entry, JarFile jar, String originalName, String targetName, File targetDirectory) throws IOException
    {
        if (extractedFile == null)
        {
            if (entry.getName().contains(originalName) && entry.getName().toLowerCase().endsWith(".jar"))
            {
                try (var in = jar.getInputStream(entry))
                {
                    extractedFile = new File(targetDirectory, targetName);
                    extractFile(in, extractedFile);
                }
            }
        }
        return extractedFile;
    }

    private void extractDistributionZip(InputStream zipInputStream, File destDir) throws IOException
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
                String entryName = labkeyWebappDirName == null
                        ? entry.getName()
                        : entry.getName().replaceFirst("^" + LABKEYWEBAPP, labkeyWebappDirName);
                File filePath = new File(destDir, entryName);
                ModuleArchive.ensureChild(destDir, filePath);
                if (!entry.isDirectory())
                {
                    // if the entry is a file, extracts it
                    extractFile(zipIn, filePath);
                }
                else
                {
                    if (filePath.exists() && filePath.getParentFile().equals(destDir))
                    {
                        throw new ConfigException("Delete or backup existing LabKey deployment at: " + filePath.getAbsolutePath());
                    }
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

    /**
     * Delete all files from the previously extracted 'distribution.zip'
     * @param webAppLocation file object for 'labkeywebapp' directory
     */
    private void deleteOldDistribution(File webAppLocation)
    {
        try
        {
            Set<File> toDelete = new HashSet<>(1 + EXPECTED_DIST_DIRS.size());
            if (webAppLocation.exists())
            {
                toDelete.add(webAppLocation);
            }
            EXPECTED_DIST_DIRS.stream()
                .map(dir -> new File(webAppLocation.getParentFile(), dir))
                .filter(File::exists)
                .forEach(toDelete::add);

            for (File f : toDelete)
            {
                LOG.debug("Deleting directory from previous LabKey installation: {}", f.getAbsolutePath());
                FileUtils.forceDelete(f);
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to delete existing LabKey installation", e);
        }
    }
}

/**
 * Build properties from 'distribution.properties' file
 *
 * @param version          the LabKey version (e.g. 24.3-SNAPSHOT)
 * @param buildUrl         optional TeamCity BUILD_URL, if distribution was produced by TeamCity
 * @param distributionName value of the 'name' property
 */
record LabKeyDistributionInfo(String version, String buildUrl, String distributionName)
{
    @Override
    public String toString()
    {
        return distributionName + ":" + version + (buildUrl != null ? ":" + buildUrl : "");
    }
}