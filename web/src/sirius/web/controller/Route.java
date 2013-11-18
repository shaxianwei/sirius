/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.controller;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import sirius.kernel.async.CallContext;
import sirius.kernel.commons.Strings;
import sirius.kernel.commons.Tuple;
import sirius.kernel.commons.Value;
import sirius.kernel.health.Exceptions;
import sirius.kernel.nls.NLS;
import sirius.web.http.WebContext;
import sirius.web.http.WebServer;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a compiled routed as a result of parsing a {@link Controller} and its methods.
 *
 * @author Andreas Haufler (aha@scireum.de)
 * @since 2013/11
 */
class Route {
    private static final Pattern EXPR = Pattern.compile("(:|#|\\$)\\{?(.+?)}?");

    private String format;
    private Pattern pattern;
    private List<Tuple<String, Object>> expressions = Lists.newArrayList();
    private Method successCallback;
    private String uri;
    private Class<?>[] parameterTypes;
    private Controller controller;

    /**
     * Compiles a method defined by a {@link Controller}
     *
     * @param uri            the URI template defined by the {@link Routed} annotation
     * @param parameterTypes the parameters defined by the method
     * @return a compiled matcher handling matching URIs
     */
    protected static Route compile(String uri, Class<?>[] parameterTypes) {
        Route result = new Route();
        result.uri = uri;
        result.parameterTypes = parameterTypes;
        result.format = uri;

        String[] elements = uri.split("/");
        StringBuilder finalPattern = new StringBuilder();
        int params = 0;
        for (String element : elements) {
            if (Strings.isFilled(element)) {
                element = element.trim();
                element = element.replace("\n", "").replace("\t", "");
                finalPattern.append("/");
                Matcher m = EXPR.matcher(element);
                if (m.matches()) {
                    String key = m.group(1).intern();
                    if (key == ":") {
                        result.expressions.add(new Tuple<String, Object>(key, Integer.parseInt(m.group(2))));
                        params++;
                    } else {
                        result.expressions.add(new Tuple<String, Object>(key, m.group(2)));
                    }
                    finalPattern.append("([^/]+)");
                } else if ("*".equals(element)) {
                    finalPattern.append("[^/]+");
                } else {
                    finalPattern.append(Pattern.quote(element));
                }
            }
        }
        if (parameterTypes.length - 1 != params) {
            throw new IllegalArgumentException(Strings.apply("Method has %d parameters, route '%s' has %d",
                                                             parameterTypes.length,
                                                             uri,
                                                             params));
        }
        if (!WebContext.class.equals(parameterTypes[0])) {
            throw new IllegalArgumentException(Strings.apply("Method needs '%s' as first parameter",
                                                             WebContext.class.getName()));
        }
        if (Strings.isEmpty(finalPattern.toString())) {
            finalPattern = new StringBuilder("/");
        }
        result.pattern = Pattern.compile(finalPattern.toString());
        return result;
    }


    /**
     * Determines if this route matches the current request.
     *
     * @param ctx          defines the current request
     * @param requestedURI contains the request uri as string
     * @return <tt>null</tt> if the route does not match or a list of extracted object from the URI as defined by the template
     */
    protected List<Object> matches(WebContext ctx, String requestedURI) {
        try {
            Matcher m = pattern.matcher(requestedURI);
            List<Object> result = new ArrayList<Object>(parameterTypes.length);
            if (m.matches()) {

                // Compare NLS (translated texts...)
                for (int i = 1; i <= m.groupCount(); i++) {
                    Tuple<String, Object> expr = expressions.get(i - 1);
                    String value = URLDecoder.decode(m.group(i), Charsets.UTF_8.name());
                    if (expr.getFirst() == "$") {
                        if (!NLS.get((String) expr.getSecond()).equalsIgnoreCase(value)) {
                            return null;
                        }
                    } else if (expr.getFirst() == "#") {
                        ctx.setAttribute((String) expr.getSecond(), value);
                    } else if (expr.getFirst() == ":") {
                        int idx = (Integer) expr.getSecond();
                        if (idx == result.size() + 1) {
                            result.add(Value.of(value).coerce(parameterTypes[idx], null));
                        } else {
                            while (result.size() < idx) {
                                result.add(null);
                            }
                            result.set(idx - 1, Value.of(value).coerce(parameterTypes[idx - 1], null));
                        }
                    }
                }
                CallContext.getCurrent().addToMDC("route", format);
                return result;
            }
            return null;
        } catch (UnsupportedEncodingException e) {
            throw Exceptions.handle(WebServer.LOG, e);
        }
    }

    protected Method getSuccessCallback() {
        return successCallback;
    }

    protected void setSuccessCallback(Method successCallback) {
        this.successCallback = successCallback;
    }

    protected void setController(Controller controller) {
        this.controller = controller;
    }

    protected Controller getController() {
        return controller;
    }

    @Override
    public String toString() {
        return uri;
    }
}


