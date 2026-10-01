package com.bookmap.plugin.rong.miniviteapp.adapters;

import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** Direct vendor transport; browsers and the localhost CORS proxy are unnecessary. */
public final class JdkHttp implements HttpPort {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    public Response request(URI uri, String method, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)); headers.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, String> returned = new java.util.LinkedHashMap<>(); response.headers().map().forEach((key, values) -> returned.put(key, String.join(",", values)));
        return new Response(response.statusCode(), response.body(), returned);
    }
}
