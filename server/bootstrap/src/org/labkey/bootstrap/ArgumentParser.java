/*
 * Copyright (c) 2008-2013 LabKey Corporation
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

import java.util.*;

/**
 * User: jeckels
 * Date: Apr 16, 2008
 */
public class ArgumentParser
{
    private List<String> _params = new ArrayList<>();
    private Map<String, String> _options = new HashMap<>();

    public ArgumentParser(String[] args)
    {
        for (String arg : args)
        {
            if (arg.startsWith("-"))
            {
                int loc = arg.indexOf("=");
                String key = (loc > 0) ? arg.substring(1, loc) : arg.substring(1);
                String value = (loc > 0) ? arg.substring(loc + 1) : "";
                _options.put(key.toLowerCase(), value);
            }
            else
            {
                _params.add(arg);
            }
        }
    }

    public boolean hasOption(String opt)
    {
        return _options.containsKey(opt.toLowerCase());
    }

    public String getOption(String opt)
    {
        return _options.get(opt.toLowerCase());
    }

    public List<String> getParameters()
    {
        return _params;
    }
}
