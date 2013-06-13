/*
 * Copyright (c) 2006-2008 LabKey Corporation
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

package org.fhcrc.cpas.bootstrap;

import org.labkey.bootstrap.LabkeyServerBootstrapClassLoader;

/**
 * Here for backwards compatibility with labkey.xml (or similar) deployment descriptors that still refer to the
 * this class by name. Using LabKeyServerBootstrapClassLoader has been preferred for many years.
 *
 * User: jeckels
 * Date: Jan 5, 2007
 */
@Deprecated
public class CpasBootstrapClassLoader extends LabkeyServerBootstrapClassLoader
{
    public CpasBootstrapClassLoader()
    {
        super();
    }

    public CpasBootstrapClassLoader(ClassLoader parent)
    {
        super(parent);
    }
}
