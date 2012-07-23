package com.github.edgarespina.mwa.wro4j;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.servlet.FilterChain;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import ro.isdc.wro.config.Context;
import ro.isdc.wro.config.jmx.WroConfiguration;
import ro.isdc.wro.extensions.model.factory.SmartWroModelFactory;
import ro.isdc.wro.http.ConfigurableWroFilter;
import ro.isdc.wro.http.WroFilter;
import ro.isdc.wro.manager.factory.BaseWroManagerFactory;
import ro.isdc.wro.manager.factory.WroManagerFactory;
import ro.isdc.wro.model.WroModel;
import ro.isdc.wro.model.factory.WroModelFactory;
import ro.isdc.wro.model.group.DefaultGroupExtractor;
import ro.isdc.wro.model.group.Group;
import ro.isdc.wro.model.resource.Resource;
import ro.isdc.wro.model.resource.locator.ClasspathUriLocator;
import ro.isdc.wro.model.resource.locator.UrlUriLocator;
import ro.isdc.wro.model.resource.locator.factory.SimpleUriLocatorFactory;
import ro.isdc.wro.model.resource.locator.factory.UriLocatorFactory;
import ro.isdc.wro.model.resource.locator.wildcard.WildcardUriLocatorSupport;
import ro.isdc.wro.model.resource.processor.ResourcePostProcessor;
import ro.isdc.wro.model.resource.processor.ResourcePreProcessor;
import ro.isdc.wro.model.resource.processor.factory.ProcessorsFactory;
import ro.isdc.wro.model.resource.processor.factory.SimpleProcessorsFactory;
import ro.isdc.wro.model.transformer.WildcardExpanderModelTransformer;
import ro.isdc.wro.model.transformer.WildcardExpanderModelTransformer.NoMoreAttemptsIOException;
import ro.isdc.wro.util.ObjectFactory;
import ro.isdc.wro.util.Transformer;

import com.github.edgarespina.mwa.FilterMapping;
import com.github.edgarespina.mwa.Mode;
import com.github.edgarespina.mwa.ModeAware;

/**
 * <p>
 * The {@link WroBaseModule} configure all the necessary infrastructure required
 * by the <a href="http://code.google.com/p/wro4j/">WebResourceOptimizer</a>
 * </p>
 * <p>
 * Features:
 * </p>
 * <ul>
 * <li>Intercept all the *.js and *.css request and apply the all the registered
 * {@link Processors processors}.
 * <li>The wro file descriptor can be written in: 'xml', 'groovy' or 'json'.
 * <li>While running in 'dev', the resource is treated as a single file (no
 * group)
 * <li>While running in 'dev', a HTML is printed if a js or css file doesn't
 * follow the rules of jsHint, jsLint or cssLint.
 * <li>While running in 'NO-dev', a group of files can be merged, minified and
 * compressed as a single bundle.
 * </ul>
 * <p>
 * Please see the {@link Processors processors} for a full list of processors.
 * </p>
 *
 * @author edgar.espina
 * @since 0.1.2
 * @see Processors
 */
public abstract class WroBaseModule {

  /**
   * Extract the group name from a URI. The class works with
   * {@link GroupPerFileModel}.
   *
   * @author edgar.espina
   * @devOnly
   */
  private static class GroupPerFileExtractor extends DefaultGroupExtractor {
    /**
     * {@inheritDoc}
     */
    @Override
    public String getGroupName(final HttpServletRequest request) {
      if (request == null) {
        throw new IllegalArgumentException("Request cannot be NULL!");
      }
      String uri = request.getRequestURI();
      uri = uri.replace(request.getContextPath(), "");
      final String groupName = fileToGroup(uri);
      return StringUtils.isEmpty(groupName) ? null : groupName;
    }
  }

  /**
   * This class create a group for every single resource in a {@link WroModel}.
   *
   * @author edgar.espina
   * @devOnly
   */
  private static class GroupPerFileModel implements Transformer<WroModel> {
    /**
     * {@inheritDoc}
     */
    @Override
    public WroModel transform(final WroModel input) throws Exception {
      Collection<Group> groups = input.getGroups();
      Map<String, Set<Resource>> map = new HashMap<String, Set<Resource>>();
      for (Group group : groups) {
        List<Resource> resources = group.getResources();
        for (Resource resource : resources) {
          String groupName = fileToGroup(resource.getUri());
          Set<Resource> newGroup = map.get(groupName);
          if (newGroup == null) {
            newGroup = new HashSet<Resource>();
            map.put(groupName, newGroup);
          }
          newGroup.add(resource);
        }
      }
      WroModel output = new WroModel();
      for (Entry<String, Set<Resource>> g : map.entrySet()) {
        Group newGroup = new Group(g.getKey());
        Set<Resource> resources = g.getValue();
        if (resources.size() > 2) {
          throw new IllegalStateException("Multiples resource " + resources
              + " for group: " + g.getKey());
        }
        for (Resource resource : resources) {
          newGroup.addResource(resource);
        }
        output.addGroup(newGroup);
      }
      return output;
    }
  }

  /**
   * A custom {@link WroConfigurationFactory}. Required by wro4j.
   *
   * @author edgar.espina
   * @since 0.1.2
   */
  private static class WroConfigurationFactory implements
      ObjectFactory<WroConfiguration> {

    /**
     * The {@link WroConfiguration}.
     */
    private WroConfiguration configuration;

    /**
     * Creates a new {@link WroConfigurationFactory}.
     *
     * @param configuration The {@link WroConfiguration}. Required.
     */
    public WroConfigurationFactory(final WroConfiguration configuration) {
      this.configuration =
          checkNotNull(configuration, "The wroConfiguration is required.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WroConfiguration create() {
      return configuration;
    }
  }

  /**
   * A {@link WroFilter} that handle exception and print html report while
   * running in 'dev'.
   *
   * @author edgar.espina
   * @since 0.1.2
   */
  private static class ExtendedWroFilter extends ConfigurableWroFilter {

    /**
     * The {@link WroConfigurationFactory}.
     */
    private WroConfigurationFactory configurationFactory;

    /**
     * The {@link WroConfiguration}.
     */
    private WroConfiguration configuration;

    /**
     * Creates a new {@link ExtendedWroFilter}.
     *
     * @param configuration The {@link WroConfiguration}. Required.
     * @param wroManagerFactory The {@link WroModelFactory. Required.
     */
    public ExtendedWroFilter(final WroConfiguration configuration,
        final WroManagerFactory wroManagerFactory) {
      this.configuration =
          checkNotNull(configuration, "The wroConfiguration is required.");
      this.configurationFactory = new WroConfigurationFactory(configuration);
      setWroManagerFactory(wroManagerFactory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ObjectFactory<WroConfiguration> newWroConfigurationFactory() {
      return configurationFactory;
    }

    /**
     * Handle the exception by printing a HTML page in 'dev' mode. {@inheritDoc}
     */
    @Override
    protected void onRuntimeException(final RuntimeException ex,
        final HttpServletResponse response, final FilterChain chain) {
      if (configuration.isDebug()) {
        HttpServletRequest request = Context.get().getRequest();
        WroProblemReporter.bestFor(ex).report(ex, request, response);
      } else {
        WroProblemReporter.DEFAULT.report(ex, null, response);
      }
    }
  }

  /**
   * A custom implementation of the servlet context uri locator that doesn't
   * depend on the wro-context object.
   *
   * @author edgar.espina
   * @since 0.1.3
   */
  private class ServletContextUriLocator extends WildcardUriLocatorSupport {

    /**
     * The prefix uri.
     */
    private static final String PREFIX = "/";

    /**
     * The servelt context.
     */
    private final ServletContext servletContext;

    /**
     * The logging system.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Creates a new {@link ServletContextUriLocator}.
     *
     * @param servletContext The servlet context. Required.
     */
    public ServletContextUriLocator(final ServletContext servletContext) {
      this.servletContext =
          checkNotNull(servletContext, "The servletContext is required.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InputStream locate(final String uri) throws IOException {
      try {
        if (getWildcardStreamLocator().hasWildcard(uri)) {
          final String fullPath = FilenameUtils.getFullPath(uri);
          final String realPath = servletContext.getRealPath(fullPath);
          if (realPath == null) {
            final String message =
                "Could not determine realPath for resource: " + uri;
            logger.error(message);
            throw new IOException(message);
          }
          return getWildcardStreamLocator().locateStream(uri,
              new File(realPath));
        }
      } catch (NoMoreAttemptsIOException ex) {
        throw ex;
      } catch (IOException ex) {
        logger
            .warn(
                "Couldn't localize the stream containing wildcard. Original "
                    + "error message: '{}'", ex.getMessage()
                    + "\".\n Trying to locate the stream without the "
                    + "wildcard.");
      }
      InputStream input = servletContext.getResourceAsStream(uri);
      Validate.notNull(input, "Resource not found: " + uri);
      return input;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean accept(final String uri) {
      return uri.trim().startsWith(PREFIX);
    }

  }

  /**
   * Intercept js and css resource and apply all the registered
   * {@link Processors}.
   *
   * @param filter The wro filter. Required.
   * @return An interceptor for js and css resources.
   */
  @Bean
  public FilterMapping wroFilterMapping(final WroFilter filter) {
    return FilterMapping.filter(
        "/**/*.js",
        "/**/*.css",
        "**/*.js",
        "**/*.css"
        ).through(filter);
  }

  /**
   * Intercept js and css resource and apply all the registered
   * {@link Processors}.
   *
   * @param configuration The {@link WroConfiguration}. Required.
   * @param wroManagerFactory The {@link WroModelFactory}. Required.
   * @return An interceptor for js and css resources.
   */
  @Bean
  public WroFilter wroFilter(final WroConfiguration configuration,
      final WroManagerFactory wroManagerFactory) {
    return new ExtendedWroFilter(configuration, wroManagerFactory);
  }

  /**
   * Creates a {@link WroConfiguration} using the application's environment.
   *
   * @param env The application's environment. Required.
   * @param mode The application's mode. Required.
   * @return A {@link WroConfiguration} using the application's environment.
   */
  @Bean
  public WroConfiguration wroConfiguration(final Environment env,
      final Mode mode) {
    checkNotNull(env, "The application's environment is required.");
    boolean debug = mode.isDev();
    boolean disableCache = true;
    boolean gzipEnabled = false;
    boolean cacheGzippedContent = false;
    long cacheUpdatePeriod = 0;
    long modelUpdatePeriod = 1;
    boolean parallelPreprocessing = false;
    if (!debug) {
      cacheGzippedContent = true;
      gzipEnabled = true;
      disableCache = false;
      modelUpdatePeriod = 0;
    }
    WroConfiguration configuration = new WroConfiguration();
    configuration.setCacheGzippedContent(env.getProperty(
        "wro.cacheGzippedContent", Boolean.class, cacheGzippedContent));
    configuration.setCacheUpdatePeriod(env.getProperty(
        "wro.cacheUpdatePeriod", Long.class, cacheUpdatePeriod));
    configuration.setConnectionTimeout(env.getProperty(
        "wro.connectionTimeout", Integer.class,
        WroConfiguration.DEFAULT_CONNECTION_TIMEOUT));
    configuration.setDebug(debug);
    configuration.setDisableCache(env.getProperty(
        "wro.disableCache", Boolean.class, disableCache));
    configuration.setEncoding(env.getProperty(
        "wro.encoding", String.class, WroConfiguration.DEFAULT_ENCODING));
    configuration.setGzipEnabled(env.getProperty(
        "wro.gzipEnabled", Boolean.class, gzipEnabled));
    configuration.setHeader(env.getProperty("wro.header"));
    configuration.setIgnoreEmptyGroup(env.getProperty(
        "wro.ignoreEmptyGroup", Boolean.class, true));
    configuration.setIgnoreMissingResources(env.getProperty(
        "wro.ignoreMissingResources", Boolean.class, true));
    configuration.setJmxEnabled(env.getProperty(
        "wro.jmxEnabled", Boolean.class, false));
    configuration.setMbeanName(env.getProperty("wro.mbeanName"));
    configuration.setModelUpdatePeriod(env.getProperty(
        "wro.modelUpdatePeriod", Long.class, modelUpdatePeriod));
    configuration.setParallelPreprocessing(env.getProperty(
        "wro.parallelPreprocessing", Boolean.class, parallelPreprocessing));
    return configuration;
  }

  /**
   * Creates a new {@link UriLocatorFactory} service. Web resources can be on
   * the classpath, web path or in remote location.
   *
   * @param servletContext The servlet context. Required.
   * @return A new {@link UriLocatorFactory} service. Web resources can be on
   *         the classpath, web path or in remote location.
   * @see ServletContextUriLocator
   * @see ClasspathUriLocator
   * @see UrlUriLocatorl
   */
  @Bean
  public UriLocatorFactory wroUriLocatorFactory(
      final ServletContext servletContext) {
    return new SimpleUriLocatorFactory()
        .addUriLocator(new ServletContextUriLocator(servletContext))
        .addUriLocator(new ClasspathUriLocator())
        .addUriLocator(new UrlUriLocator());
  }

  /**
   * Publish a {@link BaseWroManagerFactory}.
   *
   * @param environment The application's environment. Required.
   * @param mode The application's mmode. Required.
   * @param processorsFactory The user-defined {@link ProcessorsFactory}.
   *        Required.
   * @param uriLocatorFactory The {@link UriLocatorFactory}. Required.
   * @return A new {@link BaseWroManagerFactory}.
   */
  @Bean
  public BaseWroManagerFactory wroManagerFactory(final Environment environment,
      final Mode mode, final ProcessorsFactory processorsFactory,
      final UriLocatorFactory uriLocatorFactory) {
    checkNotNull(environment, "The application's environment is required.");
    checkNotNull(mode, "The application's descriptor is required.");
    checkNotNull(processorsFactory, "The processor's factory is required.");
    checkNotNull(uriLocatorFactory, "The uri locator factory is required.");
    BaseWroManagerFactory wroManagerFactory = new BaseWroManagerFactory();
    wroManagerFactory
        .setUriLocatorFactory(uriLocatorFactory)
        .setModelFactory(wroModelFactory());

    wroManagerFactory
        .addModelTransformer(new WildcardExpanderModelTransformer());
    if (mode.isDev()) {
      wroManagerFactory.addModelTransformer(new GroupPerFileModel());
      wroManagerFactory.setGroupExtractor(new GroupPerFileExtractor());
      wroManagerFactory.setProcessorsFactory(processorsFactory(
          mode, processorsFactory, uriLocatorFactory,
          environment));
    } else {
      wroManagerFactory.setProcessorsFactory(processorsFactory(
          mode, processorsFactory, uriLocatorFactory,
          environment));
    }
    return wroManagerFactory;
  }

  /**
   * Creates a new {@link WroModelFactory}.
   *
   * @return A new {@link WroModelFactory}.
   */
  @Bean
  public WroModelFactory wroModelFactory() {
    return new SmartWroModelFactory();
  }

  /**
   * Translate wro error as HTML if the application is running in 'dev' mode.
   *
   * @param mode The application's mode. Required.
   * @param filter The wroFileter. Required.
   * @param uriLocatorFactory The uri locator. Required.
   * @return A HTML reporter for 'dev'.
   */
  @Bean
  public HandlerInterceptor wroProblemReporterInterceptor(
      final Mode mode, final WroFilter filter,
      final UriLocatorFactory uriLocatorFactory) {
    if (mode.isDev()) {
      return new WroProblemReporterInterceptor(filter, uriLocatorFactory);
    }
    // Do nothing.
    return new HandlerInterceptorAdapter() {

    };
  }

  /**
   * Turn off some {@link ResourcePostProcessor} and
   * {@link ResourcePreProcessor} if they don't match the given environment.
   *
   * @param mode The application's mode.
   * @param processors The candidate processor's factory.
   * @param uriLocatorFactory The uri locator factory.
   * @param environment The application's environment.
   * @return A new {@link ProcessorsFactory} for the given environment.
   */
  private static ProcessorsFactory processorsFactory(final Mode mode,
      final ProcessorsFactory processors,
      final UriLocatorFactory uriLocatorFactory,
      final Environment environment) {
    SimpleProcessorsFactory result = new SimpleProcessorsFactory();
    for (ResourcePreProcessor processor : processors.getPreProcessors()) {
      if (applyProcessor(mode, uriLocatorFactory, environment, processor)) {
        result.addPreProcessor(processor);
      }
    }
    for (ResourcePostProcessor processor : processors.getPostProcessors()) {
      if (applyProcessor(mode, uriLocatorFactory, environment, processor)) {
        result.addPostProcessor(processor);
      }
    }
    return result;
  }

  /**
   * True if the given processor apply for the enviroment.
   *
   * @param mode The application's environment.
   * @param uriLocatorFactory The uri locator factory.
   * @param environment The application's environment.
   * @param processor The candidate processor.
   * @return True if the given processor apply for the enviroment.
   */
  private static boolean applyProcessor(final Mode mode,
      final UriLocatorFactory uriLocatorFactory, final Environment environment,
      final Object processor) {
    // Check for specific contract
    if (processor instanceof UriLocatorFactoryAware) {
      ((UriLocatorFactoryAware) processor)
          .setUriLocatorFactory(uriLocatorFactory);
    }
    if (processor instanceof EnvironmentAware) {
      ((EnvironmentAware) processor).setEnvironment(environment);
    }
    if (processor instanceof ModeAware) {
      ((ModeAware) processor).setMode(mode);
    }
    return true;
  }

  /**
   * Convert the given file's name to a wro group's name.
   *
   * @param filename The file's name.
   * @return The group's name.
   */
  private static String fileToGroup(final String filename) {
    String group = filename;
    if (group.startsWith("/")) {
      group = group.substring(1);
    }
    group = FilenameUtils.removeExtension(group);
    return group.replace("/", "_");
  }
}