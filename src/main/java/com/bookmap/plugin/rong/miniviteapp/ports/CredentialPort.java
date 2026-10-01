package com.bookmap.plugin.rong.miniviteapp.ports;

import com.google.gson.JsonObject;

public interface CredentialPort {
    JsonObject loadSchwab();
    void saveSchwab(JsonObject credentials) throws Exception;
}
