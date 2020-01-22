#if (${PACKAGE_NAME} && ${PACKAGE_NAME} != "")package ${PACKAGE_NAME};#end

import org.labkey.test.Locator;
import org.labkey.test.components.Component;
import org.labkey.test.components.WebDriverComponent;
import org.labkey.test.components.html.Input;
import org.labkey.test.pages.LabKeyPage;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import static org.labkey.test.components.html.Input.Input;

// TODO: Component classes should contain all functionality for a component
public class ${NAME} extends WebDriverComponent<${NAME}.ElementCache>
{
    private final WebElement _el;
    private final WebDriver _driver;

    protected ${NAME}(WebElement element, WebDriver driver)
    {
        _el = element;
        _driver = driver;
    }

    @Override
    public WebElement getComponentElement()
    {
        return _el;
    }

    @Override
    public WebDriver getDriver()
    {
        return _driver;
    }

    // TODO: Add methods for actual interactions
    public ${NAME} setInput(String value)
    {
        elementCache().input.set(value);

        // TODO: Methods that don't navigate should return this object
        return this;
    }

    public LabKeyPage clickButton()
    {
        getWrapper().clickAndWait(elementCache().button);

        // TODO: Methods that navigate should return an appropriate page object
        return new LabKeyPage(getDriver());
    }

    @Override
    protected ElementCache newElementCache()
    {
        return new ElementCache();
    }

    /**
     * TODO
     *  ElementCache should be responsible for finding and storing all elements and
     *  sub-components that the component contains
     */
    protected class ElementCache extends Component<?>.ElementCache
    {
        // TODO: Add elements that are in the component
        final Input input = Input(Locator.css("input"), getDriver()).findWhenNeeded(this);
        final WebElement button = Locator.css("button").findWhenNeeded(this);
    }

    /** 
     * TODO:
     *  For components that are, essentially, singletons on a page, you may want to omit this Finder class
     *  Note that even in that case, a Finder class can be useful for lazily finding components
     *  Usage: 'new Component.ComponentFinder(getDriver()).withTitle("title").findWhenNeeded();' 
     */ 
    public static class ${NAME}Finder extends WebDriverComponent.WebDriverComponentFinder<${NAME}, ${NAME}Finder>
    {
        // TODO: This locator should find all instances of this component
        private final Locator.XPathLocator _baseLocator = Locator.tagWithClass("div", "my-component");
        private String _title = null;

        public ${NAME}Finder(WebDriver driver)
        {
            super(driver);
        }

        public ${NAME}Finder withTitle(String title)
        {
            _title = title; 
            return this;
        }

        @Override
        protected ${NAME} construct(WebElement el, WebDriver driver)
        {
            return new ${NAME}(el, driver);
        }

        /**
         * TODO:
         *  Add methods and fields, as appropriate, to build a Locator that will find the element(s)
         *  that this component represents
         */
        @Override
        protected Locator locator()
        {
            if (_title != null)
                return _baseLocator.withAttribute("title", _title);
            else
                return _baseLocator;
        }
    }
}
