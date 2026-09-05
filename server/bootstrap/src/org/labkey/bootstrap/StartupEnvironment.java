/*
 * Copyright (c) 2026 LabKey Corporation
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

import java.io.File;

/**
 * JVM-wide settings that have to be established before the rest of the server loads, shared by the entry points that
 * might get there first.
 */
public class StartupEnvironment
{
    /** Directory the server was launched from, and where labkeyBootstrap.jar and ./logs live. */
    public static final String LABKEY_HOME_PROPERTY_NAME = "labkey.home";

    private static final String HEADLESS_PROPERTY_NAME = "java.awt.headless";

    private StartupEnvironment()
    {
    }

    /** Some modules die during startup on some platforms if AWT isn't headless. */
    public static void ensureHeadless()
    {
        if (System.getProperty(HEADLESS_PROPERTY_NAME) == null)
            System.setProperty(HEADLESS_PROPERTY_NAME, "true");
    }

    public static String ensureLabKeyHomeSet(File directory)
    {
        if (System.getProperty(LABKEY_HOME_PROPERTY_NAME) == null)
            System.setProperty(LABKEY_HOME_PROPERTY_NAME, directory.getAbsolutePath());
        return System.getProperty(LABKEY_HOME_PROPERTY_NAME);
    }

    /** Null before the boot layer has run, which is the case in tools that bootstrap their own JVM. */
    public static File getLabKeyHome()
    {
        String path = System.getProperty(LABKEY_HOME_PROPERTY_NAME);
        return path == null ? null : new File(path);
    }
}
