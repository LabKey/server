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
package org.apache.tools.ant.taskdefs;

import org.apache.tools.ant.Task;

import java.io.OutputStream;

/**
 * LabKey's stub version of ant's LogOutputStream. Tomcat's JspC requires this class; providing it and a few others in our
 * bootstrap jar eliminates the need to distribute and deploy ant.jar into the /tomcat/lib directory.
 */
@SuppressWarnings("unused")
public class LogOutputStream extends OutputStream
{
    // JspC constructs this only when getProject() is non-null, which never happens with our Task stub
    public LogOutputStream(Task task, int level)
    {
    }

    @Override
    public void write(int b)
    {
    }
}
