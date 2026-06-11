/*
 * Copyright (c) 2018-2026 LabKey Corporation
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
#if (${PACKAGE_NAME} && ${PACKAGE_NAME} != "")package ${PACKAGE_NAME};#end

import org.labkey.test.Locator;
import org.labkey.test.WebDriverWrapper;
import org.labkey.test.WebTestHelper;
import org.labkey.test.pages.LabKeyPage;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

// TODO: Page classes should contain all functionality for a single page/action
public class ${NAME} extends LabKeyPage<${NAME}.ElementCache>
{
    public ${NAME}(WebDriver driver)
    {
        super(driver);
    }

    public static ${NAME} beginAt(WebDriverWrapper webDriverWrapper)
    {
        return beginAt(webDriverWrapper, webDriverWrapper.getCurrentContainerPath());
    }

    public static ${NAME} beginAt(WebDriverWrapper webDriverWrapper, String containerPath)
    {
        webDriverWrapper.beginAt(WebTestHelper.buildURL("controller", containerPath, "action"));
        return new ${NAME}(webDriverWrapper.getDriver());
    }

    // TODO: Add methods for other actions on this page
    public LabKeyPage<?> clickButton()
    {
        clickAndWait(elementCache().example);
        
        // TODO: Methods that navigate should return an appropriate page object
        return new LabKeyPage<>(getDriver());
    }

    @Override
    protected ElementCache newElementCache()
    {
        return new ElementCache();
    }

    protected class ElementCache extends LabKeyPage<ElementCache>.ElementCache
    {
        // TODO: Add other elements that are on the page
        final WebElement example = Locator.css("button").findWhenNeeded(this);
    }
}
