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

/*
* User: Dave
* Date: Dec 9, 2008
* Time: 10:56:03 AM
*/
public class BaseComparator
{
    public static final int FILE_TIMESTAMP_ERROR = 2000;

    protected int compareTimes(long t1, long t2)
    {
        if(t1 >= (t2 - FILE_TIMESTAMP_ERROR) && t1 <= (t2 + FILE_TIMESTAMP_ERROR))
            return 0;
        else if(t1 < t2)
            return -1;
        else
            return 1;
    }

    protected int compareSizes(long s1, long s2)
    {
        if(s1 == s2)
            return 0;
        else if(s1 < s2)
            return -1;
        else
            return 1;
    }

}