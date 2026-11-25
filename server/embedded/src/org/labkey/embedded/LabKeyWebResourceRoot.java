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
package org.labkey.embedded;

import org.apache.catalina.Context;
import org.apache.catalina.WebResource;
import org.apache.catalina.webresources.EmptyResource;
import org.apache.catalina.webresources.StandardRoot;

/**
 * Module classes load from module jars, not the webapp, yet Tomcat probes WEB-INF/classes for every class and resource
 * lookup, canonicalizing the path on disk each time. We don't use WEB-INF/classes or WEB-INF/lib, so short-circuit.
 */
class LabKeyWebResourceRoot extends StandardRoot
{
    private static final String WEB_INF_CLASSES = "/WEB-INF/classes";

    LabKeyWebResourceRoot(Context context)
    {
        super(context);
    }

    @Override
    public WebResource getClassLoaderResource(String path)
    {
        return new EmptyResource(this, WEB_INF_CLASSES + path);
    }

    @Override
    public WebResource[] getClassLoaderResources(String path)
    {
        return new WebResource[0];
    }
}
