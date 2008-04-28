package org.labkey.bootstrap;

import java.util.*;

/**
 * User: jeckels
 * Date: Apr 16, 2008
 */
public class ArgumentParser
{
    private List<String> _params = new ArrayList<String>();
    private Map<String, String> _options = new HashMap<String, String>();    

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
