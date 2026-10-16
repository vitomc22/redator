package com.redator.corretor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.llm.ollama")
public class OllamaProperties {
    private String baseUrl = "http://127.0.0.1:11434";
    private String model = "gemma4:e2b-it-q4_K_M";
    private int numCtx = 8192, numPredict = 1200, numThread = 4;
    private double temperature = 0;
    private long seed = 42;
    private String keepAlive = "5m";
    private int connectTimeoutSeconds = 3, timeoutSeconds = 600;
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v; }
    public String getModel() { return model; }
    public void setModel(String v) { model = v; }
    public int getNumCtx() { return numCtx; }
    public void setNumCtx(int v) { numCtx = v; }
    public int getNumPredict() { return numPredict; }
    public void setNumPredict(int v) { numPredict = v; }
    public int getNumThread() { return numThread; }
    public void setNumThread(int v) { numThread = v; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double v) { temperature = v; }
    public long getSeed() { return seed; }
    public void setSeed(long v) { seed = v; }
    public String getKeepAlive() { return keepAlive; }
    public void setKeepAlive(String v) { keepAlive = v; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int v) { connectTimeoutSeconds = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { timeoutSeconds = v; }
}
