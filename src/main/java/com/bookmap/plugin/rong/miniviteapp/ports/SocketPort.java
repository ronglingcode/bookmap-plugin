package com.bookmap.plugin.rong.miniviteapp.ports;

public interface SocketPort {
    Connection open(String url, Handlers handlers) throws Exception;
    interface Connection { void send(String message); void close(); }
    interface Handlers { void opened(Connection socket); void message(Connection socket, String data); void closed(); void failed(); }
    interface Scheduler { Runnable after(long delayMs, Runnable task); }
}
