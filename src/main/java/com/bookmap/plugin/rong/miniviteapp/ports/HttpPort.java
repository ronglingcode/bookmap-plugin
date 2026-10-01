package com.bookmap.plugin.rong.miniviteapp.ports;

import java.net.URI;
import java.util.Map;

/** Vendor clients can be exercised without network, credentials, or Bookmap. */
@FunctionalInterface
public interface HttpPort {
    Response request(URI uri, String method, Map<String, String> headers, String body) throws Exception;

    final class Response {
        public final int status;
        public final String body;
        public final Map<String, String> headers;
        public Response(int status, String body) { this(status, body, Map.of()); }
        public Response(int status, String body, Map<String, String> headers) { this.status = status; this.body = body; this.headers = Map.copyOf(headers); }
        public String header(String name) { return headers.entrySet().stream().filter(value -> value.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(""); }
    }
}
