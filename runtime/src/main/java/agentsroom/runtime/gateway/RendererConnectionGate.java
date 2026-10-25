package agentsroom.runtime.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Admits a request only with the launch token and an allowed Origin, before any WebSocket upgrade
 * (ADR 0001 decision 4). It guards every path, not just {@code /ws}: the runtime serves nothing else.
 *
 * <ol>
 *   <li>{@code ?token=} missing or wrong → 401. Compared in constant time.
 *   <li>{@code Origin} present and not allowed → 403. A missing Origin passes: only non-browser
 *       clients (Node, curl) omit it, and they still need the token.
 * </ol>
 *
 * <p>Neither the token nor the query string is ever logged.
 */
public final class RendererConnectionGate implements WebFilter, Ordered {

    public static final String TOKEN_PARAMETER = "token";

    private static final int MAX_LOGGED_ORIGIN_LENGTH = 100;
    private static final Logger log = LoggerFactory.getLogger(RendererConnectionGate.class);

    private final byte[] token;
    private final Set<String> allowedOrigins;

    public RendererConnectionGate(String token, Set<String> allowedOrigins) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.allowedOrigins = Set.copyOf(allowedOrigins);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!tokenMatches(request.getQueryParams().getFirst(TOKEN_PARAMETER))) {
            log.warn("Rejected a renderer connection: missing or wrong token");
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
        String origin = request.getHeaders().getOrigin();
        if (origin != null && !allowedOrigins.contains(origin)) {
            log.warn("Rejected a renderer connection: Origin '{}' is not allowed", truncate(origin));
            return reject(exchange, HttpStatus.FORBIDDEN);
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private boolean tokenMatches(String supplied) {
        // isEqual's running time depends on the first argument's length only, so the supplied value
        // goes first and the expected token's length does not leak.
        return supplied != null && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), token);
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    private static String truncate(String origin) {
        if (origin.length() <= MAX_LOGGED_ORIGIN_LENGTH) {
            return origin;
        }
        return origin.substring(0, MAX_LOGGED_ORIGIN_LENGTH) + "...";
    }
}
