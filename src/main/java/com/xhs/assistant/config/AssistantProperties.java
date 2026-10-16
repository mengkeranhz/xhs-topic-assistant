package com.xhs.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "assistant")
public class AssistantProperties {
    private final Browser browser = new Browser();
    private final Model model = new Model();
    private final Storage storage = new Storage();
    private final Incremental incremental = new Incremental();

    public Browser getBrowser() { return browser; }
    public Model getModel() { return model; }
    public Storage getStorage() { return storage; }
    public Incremental getIncremental() { return incremental; }

    public static class Browser {
        private String cdpUrl = "http://127.0.0.1:9222";
        private boolean visible = true;
        private int searchResultLimit = 100;
        private int detailLimit = 20;
        private int maxImagesPerPost = 6;
        private long minActionIntervalMs = 4000;
        private long maxActionIntervalMs = 9000;
        private long detailMinIntervalMs = 12000;
        private long detailMaxIntervalMs = 25000;
        private int pageTimeoutMs = 20000;
        private int maxContinuousCaptchaHits = 2;
        private long taskTimeoutSeconds = 900;

        public String getCdpUrl() { return cdpUrl; }
        public void setCdpUrl(String v) { this.cdpUrl = v; }
        public boolean isVisible() { return visible; }
        public void setVisible(boolean v) { this.visible = v; }
        public int getSearchResultLimit() { return searchResultLimit; }
        public void setSearchResultLimit(int v) { this.searchResultLimit = v; }
        public int getDetailLimit() { return detailLimit; }
        public void setDetailLimit(int v) { this.detailLimit = v; }
        public int getMaxImagesPerPost() { return maxImagesPerPost; }
        public void setMaxImagesPerPost(int v) { this.maxImagesPerPost = v; }
        public long getMinActionIntervalMs() { return minActionIntervalMs; }
        public void setMinActionIntervalMs(long v) { this.minActionIntervalMs = v; }
        public long getMaxActionIntervalMs() { return maxActionIntervalMs; }
        public void setMaxActionIntervalMs(long v) { this.maxActionIntervalMs = v; }
        public long getDetailMinIntervalMs() { return detailMinIntervalMs; }
        public void setDetailMinIntervalMs(long v) { this.detailMinIntervalMs = v; }
        public long getDetailMaxIntervalMs() { return detailMaxIntervalMs; }
        public void setDetailMaxIntervalMs(long v) { this.detailMaxIntervalMs = v; }
        public int getPageTimeoutMs() { return pageTimeoutMs; }
        public void setPageTimeoutMs(int v) { this.pageTimeoutMs = v; }
        public int getMaxContinuousCaptchaHits() { return maxContinuousCaptchaHits; }
        public void setMaxContinuousCaptchaHits(int v) { this.maxContinuousCaptchaHits = v; }
        public long getTaskTimeoutSeconds() { return taskTimeoutSeconds; }
        public void setTaskTimeoutSeconds(long v) { this.taskTimeoutSeconds = v; }
    }

    public static class Model {
        private String provider = "openai";
        private final OpenAi openai = new OpenAi();
        private final Anthropic anthropic = new Anthropic();
        private double temperature = 0.2;
        private int timeoutSeconds = 90;
        private int maxConcurrency = 2;
        private int maxCallsPerTask = 80;
        private long maxInputTokensPerTask = 120000;
        private boolean allowOfflineFallback = true;
        private int maxAgentRounds = 8;
        private int maxToolCallsPerRound = 4;
        private int maxFinalRepairs = 2;

        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public OpenAi getOpenai() { return openai; }
        public Anthropic getAnthropic() { return anthropic; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double v) { this.temperature = v; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
        public int getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(int v) { this.maxConcurrency = v; }
        public int getMaxCallsPerTask() { return maxCallsPerTask; }
        public void setMaxCallsPerTask(int v) { this.maxCallsPerTask = v; }
        public long getMaxInputTokensPerTask() { return maxInputTokensPerTask; }
        public void setMaxInputTokensPerTask(long v) { this.maxInputTokensPerTask = v; }
        public boolean isAllowOfflineFallback() { return allowOfflineFallback; }
        public void setAllowOfflineFallback(boolean v) { this.allowOfflineFallback = v; }
        public int getMaxAgentRounds() { return maxAgentRounds; }
        public void setMaxAgentRounds(int v) { this.maxAgentRounds = v; }
        public int getMaxToolCallsPerRound() { return maxToolCallsPerRound; }
        public void setMaxToolCallsPerRound(int v) { this.maxToolCallsPerRound = v; }
        public int getMaxFinalRepairs() { return maxFinalRepairs; }
        public void setMaxFinalRepairs(int v) { this.maxFinalRepairs = v; }
    }

    public static class OpenAi {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "${OPENAI_API_KEY:}";
        private String chatModel = "gpt-4o-mini";
        private String visionModel = "gpt-4o-mini";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getChatModel() { return chatModel; }
        public void setChatModel(String v) { this.chatModel = v; }
        public String getVisionModel() { return visionModel; }
        public void setVisionModel(String v) { this.visionModel = v; }
    }

    public static class Anthropic {
        private String baseUrl = "https://api.anthropic.com";
        private String apiKey = "${ANTHROPIC_API_KEY:}";
        private String apiVersion = "2023-06-01";
        private String chatModel = "claude-3-5-sonnet-latest";
        private String visionModel = "claude-3-5-sonnet-latest";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getApiVersion() { return apiVersion; }
        public void setApiVersion(String v) { this.apiVersion = v; }
        public String getChatModel() { return chatModel; }
        public void setChatModel(String v) { this.chatModel = v; }
        public String getVisionModel() { return visionModel; }
        public void setVisionModel(String v) { this.visionModel = v; }
    }

    public static class Storage {
        private String root = "./data";
        private boolean keepPageSnapshot = true;
        private int maxPostRawChars = 20000;

        public String getRoot() { return root; }
        public void setRoot(String v) { this.root = v; }
        public boolean isKeepPageSnapshot() { return keepPageSnapshot; }
        public void setKeepPageSnapshot(boolean v) { this.keepPageSnapshot = v; }
        public int getMaxPostRawChars() { return maxPostRawChars; }
        public void setMaxPostRawChars(int v) { this.maxPostRawChars = v; }
    }

    public static class Incremental {
        private boolean enabled = true;
        private long minRerunIntervalSeconds = 1800;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public long getMinRerunIntervalSeconds() { return minRerunIntervalSeconds; }
        public void setMinRerunIntervalSeconds(long v) { this.minRerunIntervalSeconds = v; }
    }
}
