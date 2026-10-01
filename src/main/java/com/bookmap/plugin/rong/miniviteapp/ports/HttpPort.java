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
        public Response(int status, String body) { this.status = status; this.body = body; }
    }
}
