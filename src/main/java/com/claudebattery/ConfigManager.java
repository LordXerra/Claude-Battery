package com.claudebattery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.file.*;

public class ConfigManager {

    private static final String CONFIG_DIR =
            System.getProperty("user.home") + "/.claude-battery";
    private static final String CONFIG_FILE = CONFIG_DIR + "/config.json";
    private static final String USAGE_FILE  = CONFIG_DIR + "/usage.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Config config;

    public ConfigManager() {
        ensureDir();
        config = loadConfig();
    }

    private void ensureDir() {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
        } catch (IOException e) {
            System.err.println("Cannot create config dir: " + e.getMessage());
        }
    }

    public Config getConfig() { return config; }

    public void saveConfig(Config cfg) {
        this.config = cfg;
        try (Writer w = new FileWriter(CONFIG_FILE)) {
            gson.toJson(cfg, w);
        } catch (IOException e) {
            System.err.println("Cannot save config: " + e.getMessage());
        }
    }

    private Config loadConfig() {
        File f = new File(CONFIG_FILE);
        if (!f.exists()) {
            Config def = new Config();
            saveConfig(def);
            return def;
        }
        try (Reader r = new FileReader(f)) {
            Config loaded = gson.fromJson(r, Config.class);
            return loaded != null ? loaded : new Config();
        } catch (IOException e) {
            System.err.println("Cannot read config: " + e.getMessage());
            return new Config();
        }
    }

    public String getUsageFilePath() { return USAGE_FILE; }
    public String getConfigDir()     { return CONFIG_DIR; }
}
