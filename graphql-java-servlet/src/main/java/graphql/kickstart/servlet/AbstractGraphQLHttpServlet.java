package graphql.kickstart.servlet;

import static graphql.kickstart.execution.GraphQLRequest.createQueryOnlyRequest;

import graphql.ExecutionResult;
import graphql.kickstart.execution.GraphQLObjectMapper;
import graphql.kickstart.execution.GraphQLQueryInvoker;
import graphql.kickstart.execution.GraphQLRequest;
import graphql.kickstart.execution.input.GraphQLSingleInvocationInput;
import graphql.kickstart.servlet.cache.CachingHttpRequestHandlerImpl;
import graphql.kickstart.servlet.core.GraphQLMBean;
import graphql.kickstart.servlet.core.GraphQLServletListener;
import graphql.kickstart.servlet.input.GraphQLInvocationInputFactory;
import graphql.schema.GraphQLFieldDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Andrew Potter
 */
@Slf4j
public abstract class AbstractGraphQLHttpServlet extends HttpServlet implements Servlet,
    GraphQLMBean {

  /**
   * @deprecated use {@link #getConfiguration()} instead
   */
  @Deprecated
  private final List<GraphQLServletListener> listeners;
  private GraphQLConfiguration configuration;
  private HttpRequestHandler requestHandler;

  public AbstractGraphQLHttpServlet() {
    this(null);
  }

  public AbstractGraphQLHttpServlet(List<GraphQLServletListener> listeners) {
    this.listeners = listeners != null ? new ArrayList<>(listeners) : new ArrayList<>();
  }

  /**
   * @deprecated override {@link #getConfiguration()} instead
   */
  @Deprecated
  protected abstract GraphQLQueryInvoker getQueryInvoker();

  /**
   * @deprecated override {@link #getConfiguration()} instead
   */
  @Deprecated
  protected abstract GraphQLInvocationInputFactory getInvocationInputFactory();

  /**
   * @deprecated override {@link #getConfiguration()} instead
   */
  @Deprecated
  protected abstract GraphQLObjectMapper getGraphQLObjectMapper();

  /**
   * @deprecated override {@link #getConfiguration()} instead
   */
  @Deprecated
  protected abstract boolean isAsyncServletMode();

  protected GraphQLConfiguration getConfiguration() {
    return GraphQLConfiguration.with(getInvocationInputFactory())
        .with(getQueryInvoker())
        .with(getGraphQLObjectMapper())
        .with(isAsyncServletMode())
        .with(listeners)
        .build();
  }

  @Override
  public void init() {
    if (configuration == null) {
      this.configuration = getConfiguration();
      if (configuration.getResponseCacheManager() != null) {
        this.requestHandler = new CachingHttpRequestHandlerImpl(configuration);
      } else {
        this.requestHandler = HttpRequestHandlerFactory.create(configuration);
      }
    }
  }

  public void addListener(GraphQLServletListener servletListener) {
    if (configuration != null) {
      configuration.add(servletListener);
    } else {
      listeners.add(servletListener);
    }
  }

  public void removeListener(GraphQLServletListener servletListener) {
    if (configuration != null) {
      configuration.remove(servletListener);
    } else {
      listeners.remove(servletListener);
    }
  }

  @Override
  public String[] getQueries() {
    return configuration.getInvocationInputFactory().getSchemaProvider().getSchema().getQueryType()
        .getFieldDefinitions().stream().map(GraphQLFieldDefinition::getName).toArray(String[]::new);
  }

  @Override
  public String[] getMutations() {
    return configuration.getInvocationInputFactory().getSchemaProvider().getSchema()
        .getMutationType()
        .getFieldDefinitions().stream().map(GraphQLFieldDefinition::getName).toArray(String[]::new);
  }

  @Override
  public String executeQuery(String query) {
    try {
      GraphQLRequest graphQLRequest = createQueryOnlyRequest(query);
      GraphQLSingleInvocationInput invocationInput = configuration.getInvocationInputFactory()
          .create(graphQLRequest);
      ExecutionResult result = configuration.getGraphQLInvoker().query(invocationInput).getResult();
      return configuration.getObjectMapper().serializeResultAsJson(result);
    } catch (Exception e) {
      return e.getMessage();
    }
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
    doRequest(req, resp);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
    doRequest(req, resp);
  }

  private void doRequest(HttpServletRequest request, HttpServletResponse response) {
    init();
    List<GraphQLServletListener.RequestCallback> requestCallbacks = runListeners(
        l -> l.onRequest(request, response));

    try {
      requestHandler.handle(request, response);
      runCallbacks(requestCallbacks, c -> c.onSuccess(request, response));
    } catch (Exception t) {
      log.error("Error executing GraphQL request!", t);
      runCallbacks(requestCallbacks, c -> c.onError(request, response, t));
    } finally {
      runCallbacks(requestCallbacks, c -> c.onFinally(request, response));
    }
  }

  private <R> List<R> runListeners(Function<? super GraphQLServletListener, R> action) {
    return configuration.getListeners().stream()
        .map(listener -> {
          try {
            return action.apply(listener);
          } catch (Exception t) {
            log.error("Error running listener: {}", listener, t);
            return null;
          }
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  private <T> void runCallbacks(List<T> callbacks, Consumer<T> action) {
    callbacks.forEach(callback -> {
      try {
        action.accept(callback);
      } catch (Exception t) {
        log.error("Error running callback: {}", callback, t);
      }
    });
  }

}
