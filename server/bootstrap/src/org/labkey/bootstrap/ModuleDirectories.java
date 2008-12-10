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

import java.io.File;

/*
* User: Dave
* Date: Dec 8, 2008
* Time: 2:57:49 PM
*/

/**
 * Represents the module directories in our web application
 */
public class ModuleDirectories
{
    public static final String DEFAULT_MODULES_DIR = "modules";
    public static final String DEFAULT_EXTERNAL_MODULES_DIR = "externalModules";

    private File _modulesDirectory;
    private File _externalModulesDirectory;

    public ModuleDirectories(File webAppDirectory)
    {
        //modules dir can be specified in either labkey.modulesDir or cpas.modulesDir system props
        _modulesDirectory = getModuleDirectory(new String[]{"labkey.modulesDir", "cpas.modulesDir"},
                new File(webAppDirectory.getParentFile(), DEFAULT_MODULES_DIR), true);

        //externalModules dir can be specified in labkey.externalModulesDir system prop
        //it also may not exist at all (that's OK)
        _externalModulesDirectory = getModuleDirectory(new String[]{"labkey.externalModulesDir"},
                new File(webAppDirectory.getParentFile(), DEFAULT_EXTERNAL_MODULES_DIR), false);
    }

    public File getModulesDirectory()
    {
        return _modulesDirectory;
    }

    public File getExternalModulesDirectory()
    {
        return _externalModulesDirectory;
    }

    public File[] getAllModuleDirectories()
    {
        if(null != _externalModulesDirectory)
            return new File[]{_modulesDirectory, _externalModulesDirectory};
        else
            return new File[]{_modulesDirectory};
    }

    protected File getModuleDirectory(String[] sysProperties, File defaultPath, boolean verify)
    {
        String sysPropValue;
        for(String sysProp : sysProperties)
        {
            sysPropValue = System.getProperty(sysProp);
            if(null != sysPropValue)
            {
                File dir = new File(sysPropValue);
                if(dir.exists())
                    return dir;
                else
                    throw new IllegalArgumentException("The file path " + sysPropValue  + " specified in the -D" + sysProp + " command parameter does not exist!");
            }
        }

        if(defaultPath.exists())
            return defaultPath;
        else if(verify)
            throw new IllegalArgumentException("The default module file path " + defaultPath.getPath()  + " does not exist!");
        else
            return null;
    }
}