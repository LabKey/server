package org.labkey.embedded;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.labkey.bootstrap.ConfigException;
import org.springframework.util.StreamUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class EmbeddedExtractor
{
    private static final Log LOG = LogFactory.getLog(EmbeddedExtractor.class);
    private static final int BUFFER_SIZE = 1024 * 64;
    public static final String LABKEYWEBAPP = "labkeywebapp";
    /**
     * Directories that are expected to exist in 'distribution.zip'.
     */
    private static final Set<String> EXPECTED_DIST_DIRS = Set.of(LABKEYWEBAPP, "modules");

    private final File currentDir = new File("").getAbsoluteFile();
    private final File labkeyServerJar;

    private String labkeyWebappDirName = null;

    public EmbeddedExtractor()
    {
        File[] files = currentDir.listFiles(file -> {
            String name = file.getName().toLowerCase();
            return name.endsWith(".jar") && !name.contains("embedded") && !name.contains("labkeybootstrap");
        });

        if (files == null || files.length == 0)
        {
            labkeyServerJar = null;
            LOG.debug("Executable jar not found in " + currentDir);
        }
        else if (files.length > 1)
        {
            throw new ConfigException("Multiple jars found - " + Arrays.asList(files) + ". Must provide only one jar.");
        }
        else
        {
            labkeyServerJar = files[0];
            LOG.debug("Executable jar found: " + labkeyServerJar.getAbsolutePath());
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
            throw new ConfigException("Executable jar not found in " + currentDir);
        }

        return labkeyServerJar;
    }

    private boolean shouldExtract(File webAppLocation)
    {
        File existingVersionFile = new File(webAppLocation, "WEB-INF/classes/VERSION");
        File existingDistributionFile = new File(webAppLocation, "WEB-INF/classes/distribution");

        LabKeyDistributionInfo incomingDistribution = getDistributionInfo();

        // Fresh installation or upgrading from non-embedded Tomcat
        if (!existingVersionFile.exists() || !existingDistributionFile.exists())
        {
            LOG.info("Extracting new LabKey distribution - %s".formatted(incomingDistribution));
            return true;
        }

        String existingVersion;
        String existingDistributionName;
        try
        {
            existingVersion = Files.readString(existingVersionFile.toPath()).trim();
            existingDistributionName = Files.readString(existingDistributionFile.toPath()).trim();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }

        LabKeyDistributionInfo existingDistribution = new LabKeyDistributionInfo(existingVersion, existingDistributionName);

        if (!existingDistribution.equals(incomingDistribution))
        {
            LOG.info("Updating LabKey (%s -> %s)".formatted(existingDistribution, incomingDistribution));
            return true;
        }
        else if (incomingDistribution.buildUrl == null)
        {
            LOG.info("Extracting custom-build LabKey distribution (%s)".formatted(existingDistribution));
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
        String version = "";
        String distributionName = "";

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
                                if (!zipEntry.isDirectory() && zipEntry.getName().equals(LABKEYWEBAPP + "/WEB-INF/classes/VERSION"))
                                {
                                    version = StreamUtils.copyToString(zipIn, StandardCharsets.UTF_8).trim();
                                }
                                else if (!zipEntry.isDirectory() && zipEntry.getName().equals(LABKEYWEBAPP + "/WEB-INF/classes/distribution"))
                                {
                                    distributionName = StreamUtils.copyToString(zipIn, StandardCharsets.UTF_8).trim();
                                }
                                zipIn.closeEntry();
                                zipEntry = zipIn.getNextEntry();
                            }
                        }
                        if (version.isEmpty())
                        {
                            throw new ConfigException("Unable to determine version of distribution.");
                        }
                        if (distributionName.isEmpty())
                        {
                            throw new ConfigException("Unable to determine name of distribution.");
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
                        return new LabKeyDistributionInfo(version, distributionName);
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

    public void extractDistribution(File webAppLocation)
    {
        if (shouldExtract(webAppLocation))
        {
            labkeyWebappDirName = webAppLocation.getName();
            deleteOldDistribution(webAppLocation);
            extractExecutableJar(webAppLocation.getParentFile(), false);
        }
    }

    public void extractExecutableJar(File destDirectory, boolean remotePipeline)
    {
        try
        {
            try (JarFile jar = new JarFile(verifyJar()))
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
                            extractDistributionZip(distInputStream, destDirectory);
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
                LOG.debug("Deleting directory from previous LabKey installation: " + f.getAbsolutePath());
                FileUtils.forceDelete(f);
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to delete existing LabKey installation", e);
        }
    }
}

class LabKeyDistributionInfo
{
    final String version;
    final String buildUrl;
    final String distributionName;

    /**
     * 'VERSION' file is expected to contain one or two lines. The LabKey version (e.g. 24.3-SNAPSHOT) is the first line.
     * The TeamCity BUILD_URL is the second line if the distribution was produced by TeamCity
     * 'distribution' file is expected to contain the name of the deployed distribution
     * @param versionFileContents contents of 'labkeywebapp/WEB-INF/classes/VERSION'
     * @param distributionFileContents contents of 'labkeywebapp/WEB-INF/classes/distribution'
     */
    public LabKeyDistributionInfo(String versionFileContents, String distributionFileContents)
    {
        String[] splitVersion = versionFileContents.trim().split("\\n");
        version = splitVersion[0];
        if (splitVersion.length > 1)
        {
            buildUrl = splitVersion[1];
        }
        else
        {
            buildUrl = null;
        }
        distributionName = distributionFileContents;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        LabKeyDistributionInfo that = (LabKeyDistributionInfo) o;

        if (!version.equals(that.version)) return false;
        if (!Objects.equals(buildUrl, that.buildUrl)) return false;
        return distributionName.equals(that.distributionName);
    }

    @Override
    public int hashCode()
    {
        int result = version.hashCode();
        result = 31 * result + (buildUrl != null ? buildUrl.hashCode() : 0);
        result = 31 * result + distributionName.hashCode();
        return result;
    }

    @Override
    public String toString()
    {
        return distributionName + ":" + version;
    }
}