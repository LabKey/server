package org.labkey.bootstrap;

import org.apache.logging.log4j.Logger;

public class Log4JLogger implements SimpleLogger
{
    private final Logger _logger;

    public Log4JLogger(Logger logger)
    {
        _logger = logger;
    }

    @Override
    public void error(Object message, Throwable t)
    {
        _logger.error(message, t);
    }

    @Override
    public void error(Object message)
    {
        _logger.error(message);
    }

    @Override
    public void info(Object message)
    {
        _logger.info(message);
    }

}
