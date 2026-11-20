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
import org.apache.catalina.LifecycleException;
import org.apache.catalina.WebResource;
import org.apache.catalina.webresources.EmptyResource;
import org.apache.catalina.webresources.StandardRoot;

/**
 * Module classes load from module jars, not the webapp, yet Tomcat probes WEB-INF/classes for every class and
 * canonicalizes each missing path on disk. When the webapp supplies no classes, answer those probes without the disk.
 */
class LabKeyWebResourceRoot extends StandardRoot
{
    private static final String WEB_INF_CLASSES = "/WEB-INF/classes";

    private volatile boolean _noWebappClasses = false;

    LabKeyWebResourceRoot(Context context)
    {
        super(context);
    }

    @Override
    protected void startInternal() throws LifecycleException
    {
        _noWebappClasses = false;
        super.startInternal();
        // WEB-INF/lib jars register as class resources; anything else serving WEB-INF/classes shows up in the lookup
        _noWebappClasses = getClassResources().length == 0 && !getResource(WEB_INF_CLASSES, false, true).exists();
    }

    @Override
    public WebResource getClassLoaderResource(String path)
    {
        return _noWebappClasses ? new EmptyResource(this, WEB_INF_CLASSES + path) : super.getClassLoaderResource(path);
    }

    @Override
    public WebResource[] getClassLoaderResources(String path)
    {
        return _noWebappClasses ? new WebResource[0] : super.getClassLoaderResources(path);
    }
}
