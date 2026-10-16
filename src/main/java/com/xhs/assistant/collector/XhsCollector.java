package com.xhs.assistant.collector;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import com.xhs.assistant.config.AssistantProperties;
import com.xhs.assistant.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.*;

@Component
public class XhsCollector {
    private static final Logger log = LoggerFactory.getLogger(XhsCollector.class);
    private static final Pattern ID = Pattern.compile("/(?:search_result|explore|discovery/item)/([A-Za-z0-9]+)");
    private final AssistantProperties config;

    public XhsCollector(AssistantProperties config) { this.config = config; }

    public record DetailResult(Post post, List<byte[]> images) {}

    public List<Post> collectCandidates(String keyword) {
        try (Playwright playwright = createPlaywrightWithoutBrowserDownload()) {
            try (Browser browser = playwright.chromium().connectOverCDP(config.getBrowser().getCdpUrl())) {
                BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
                try (Page page = context.newPage()) {
                    page.setDefaultTimeout(config.getBrowser().getPageTimeoutMs());
                    String url = "https://www.xiaohongshu.com/search_result?keyword=" +
                            java.net.URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&source=web_explore_feed";
                    page.navigate(url);
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    ensureInteractive(page);
                    Map<String, Post> result = new LinkedHashMap<>();
                    int noNew = 0;
                    long deadline = System.currentTimeMillis() + 120000L;
                    while (result.size() < config.getBrowser().getSearchResultLimit()
                            && noNew < 3 && System.currentTimeMillis() < deadline) {
                        int before = result.size();
                        result.putAll(extractCards(page, keyword));
                        if (result.size() == before) noNew++;
                        else noNew = 0;
                        if (result.size() >= config.getBrowser().getSearchResultLimit()) break;
                        page.mouse().wheel(0, 700 + ThreadLocalRandom.current().nextInt(500));
                        page.waitForTimeout(random(config.getBrowser().getMinActionIntervalMs(), config.getBrowser().getMaxActionIntervalMs()));
                    }
                    log.info("Collected {} candidate posts for {}", result.size(), keyword);
                    return new ArrayList<>(result.values());
                }
            }
        }
    }

    private Playwright createPlaywrightWithoutBrowserDownload() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        return Playwright.create(new Playwright.CreateOptions().setEnv(env));
    }

    public DetailResult collectDetail(Post candidate) {
        if (candidate == null || candidate.url == null || !candidate.url.contains("xsec_token=")) {
            throw new NoteUnavailableException(candidate == null ? "" : candidate.postId,
                    candidate == null ? "" : candidate.url, "MISSING_XSEC_TOKEN");
        }
        try (Playwright playwright = createPlaywrightWithoutBrowserDownload()) {
            try (Browser browser = playwright.chromium().connectOverCDP(config.getBrowser().getCdpUrl())) {
                BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
                try (Page page = context.newPage()) {
                    page.setDefaultTimeout(config.getBrowser().getPageTimeoutMs());
                    page.navigate(candidate.url);
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    page.waitForTimeout(random(config.getBrowser().getDetailMinIntervalMs(), config.getBrowser().getDetailMaxIntervalMs()));
                    ensureInteractive(page);
                    ensureDetailAvailable(page, candidate);
                    Post post = clone(candidate);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) page.evaluate("""
                            () => {
                              const q = (...ss) => ss.map(s => document.querySelector(s)).find(Boolean);
                              const el = q('[class*="title"]', 'h1');
                              const body = q('[class*="desc"]', '[class*="note-text"]', '[class*="content"]');
                              const author = q('[class*="author"]', '[class*="username"]', '.user');
                              const date = q('[class*="date"]', '[class*="publish"]');
                              const stats = q('[class*="interact"]', '[class*="engage"]', '[class*="bottom"]');
                              const images = Array.from(document.images)
                                .filter(img => img.naturalWidth >= 220 && img.naturalHeight >= 120)
                                .slice(0, 6)
                                .map((img, i) => ({i: i+1, src: img.currentSrc || img.src, w: img.naturalWidth, h: img.naturalHeight}));
                              return {title: el?.innerText?.trim() || '', body: body?.innerText?.trim() || '',
                                      author: author?.innerText?.trim() || '', date: date?.innerText?.trim() || '',
                                      stats: stats?.innerText?.trim() || document.body.innerText, images};
                            }
                            """);
                    Map<String, Object> state = initialStateNoteCards(page)
                            .getOrDefault(candidate.postId, Map.of());
                    overlayDetailState(data, state);
                    applyDetail(post, data);
                    List<byte[]> images = captureImages(page, post.url);
                    return new DetailResult(post, images);
                }
            }
        }
    }

    private Map<String, Post> extractCards(Page page, String keyword) {
        Map<String, Map<String, Object>> stateCards = initialStateNoteCards(page);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) page.evaluate("""
                () => Array.from(document.querySelectorAll('a[href]')).map(a => {
                  const card = a.closest('section, article, li, [class*="note"], [class*="card"]') || a.parentElement;
                  const text = card?.innerText || a.innerText || '';
                  const attr = a.getAttribute('href') || '';
                  const dataHref = a.dataset?.href || a.getAttribute('data-href') || '';
                  const html = card?.outerHTML || a.outerHTML || '';
                  return {href: a.href, attr, dataHref, html, text};
                }).filter(x => /xiaohongshu\\.com\\/(search_result|explore|discovery\\/item)\\//.test(x.href + ' ' + x.attr + ' ' + x.dataHref))
                """);
        Map<String, Post> out = new LinkedHashMap<>();
        for (Map<String, Object> card : cards) {
            String href = String.valueOf(card.get("href"));
            String id = postId(href);
            if (id == null || out.containsKey(id)) continue;
            String text = String.valueOf(card.getOrDefault("text", ""));
            Map<String, Object> state = stateCards.getOrDefault(id, Map.of());
            Map<String, Object> noteCard = noteCard(state);
            String xsecToken = firstNonBlank(
                    xsecTokenFromUrl(String.valueOf(card.getOrDefault("attr", ""))),
                    xsecTokenFromUrl(String.valueOf(card.getOrDefault("dataHref", ""))),
                    xsecTokenFromUrl(href),
                    xsecTokenFromUrl(String.valueOf(card.getOrDefault("html", ""))),
                    stringValue(state.get("_xsec_token")));
            Post p = new Post();
            p.postId = id;
            p.xsecToken = xsecToken;
            p.url = detailUrl(id, xsecToken, href, String.valueOf(card.getOrDefault("attr", "")),
                    String.valueOf(card.getOrDefault("dataHref", "")));
            p.title = firstNonBlank(stringValue(noteCard.get("display_title"), noteCard.get("displayTitle"), noteCard.get("title")),
                    stringValue(state.get("display_title"), state.get("title")), firstLine(text));
            p.author = firstNonBlank(nestedString(noteCard, "user", "nickname"),
                    nestedString(state, "user", "nickname"), findLabel(text, List.of("作者", "博主")));
            p.publishedAt = firstNonBlank(stringValue(noteCard.get("time"), state.get("time")),
                    nestedString(noteCard, "note", "time"), findDate(text));
            p.likes = firstMetric(extractMetric(text, List.of("点赞", "赞")),
                    countFromPath(noteCard, "interact_info", "liked_count"),
                    countFromPath(noteCard, "interactInfo", "liked"),
                    countFromPath(state, "interact_info", "liked_count"));
            p.collections = firstMetric(extractMetric(text, List.of("收藏", "薯")),
                    countFromPath(noteCard, "interact_info", "collected_count"),
                    countFromPath(noteCard, "interactInfo", "collected"),
                    countFromPath(state, "interact_info", "collected_count"));
            p.comments = firstMetric(extractMetric(text, List.of("评论", "评论数")),
                    countFromPath(noteCard, "interact_info", "comment_count"),
                    countFromPath(noteCard, "interactInfo", "comment"),
                    countFromPath(state, "interact_info", "comment_count"));
            p.collectedAt = OffsetDateTime.now();
            p.collectionStatus = "CANDIDATE";
            p.contentHash = sha256(p.title + "|" + p.url);
            out.put(id, p);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> initialStateNoteCards(Page page) {
        try {
            List<Map<String, Object>> notes = (List<Map<String, Object>>) page.evaluate("""
                    () => {
                      const root = window.__INITIAL_STATE__;
                      const out = [];
                      const seen = new Set();
                      function findToken(value, depth) {
                        if (!value || depth > 10 || typeof value !== 'object') return '';
                        for (const [key, child] of Object.entries(value)) {
                          if (/^xsec[_-]?token$/i.test(key) && typeof child === 'string' && child) return child;
                          const found = findToken(child, depth + 1);
                          if (found) return found;
                        }
                        return '';
                      }
                      function walk(value, depth) {
                        if (!value || depth > 10 || out.length > 300 || seen.has(value)) return;
                        if (typeof value !== 'object') return;
                        seen.add(value);
                        const card = value.note_card || value.noteCard || value;
                        const id = value.note_id || value.noteId || value.id || card.note_id || card.noteId || card.id;
                        const title = card.display_title || card.displayTitle || card.title || value.display_title || value.title;
                        if (id && title && String(id).length > 8) {
                          out.push({...value, _id: String(id), _xsec_token: findToken(value, 0)});
                        }
                        for (const key of Object.keys(value)) walk(value[key], depth + 1);
                      }
                      walk(root, 0);
                      return out;
                    }
                    """);
            Map<String, Map<String, Object>> result = new HashMap<>();
            for (Map<String, Object> note : notes) {
                String id = String.valueOf(note.getOrDefault("_id", ""));
                if (!id.isBlank()) result.put(id, note);
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    static Map<String, Object> noteCard(Map<String, Object> state) {
        if (state.get("note_card") instanceof Map<?, ?> nested) return asStringObjectMap(nested);
        if (state.get("noteCard") instanceof Map<?, ?> nested) return asStringObjectMap(nested);
        return state;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringObjectMap(Object value) {
        return (Map<String, Object>) value;
    }

    static String xsecTokenFromUrl(String value) {
        if (value == null || value.isBlank()) return "";
        Matcher matcher = Pattern.compile("xsec[__-]?token=([^&'\"<>\\s]+)", Pattern.CASE_INSENSITIVE).matcher(value);
        if (!matcher.find()) return "";
        try { return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8); }
        catch (Exception ignored) { return matcher.group(1); }
    }

    static String detailUrl(String postId, String xsecToken, String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && candidate.contains("xsec_token=") && candidate.contains(postId)) {
                String normalized = candidate.split("#")[0];
                if (normalized.startsWith("/")) normalized = "https://www.xiaohongshu.com" + normalized;
                if (!normalized.contains("xsec_source=")) {
                    normalized += (normalized.contains("?") ? "&" : "?") + "xsec_source=pc_search";
                }
                if (!normalized.contains("source=web_explore_feed")) {
                    normalized += (normalized.contains("?") ? "&" : "?") + "source=web_explore_feed";
                }
                return normalized;
            }
        }
        if (xsecToken == null || xsecToken.isBlank()) {
            return "https://www.xiaohongshu.com/explore/" + postId;
        }
        String token = encodeQueryValue(xsecToken);
        return "https://www.xiaohongshu.com/explore/" + postId +
                "?xsec_token=" + token + "&xsec_source=pc_search&source=web_explore_feed";
    }

    static String encodeQueryValue(String value) {
        try {
            String decoded = value.contains("%") ? URLDecoder.decode(value, StandardCharsets.UTF_8) : value;
            return java.net.URLEncoder.encode(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }


    private static void overlayDetailState(Map<String, Object> data, Map<String, Object> state) {
        Map<String, Object> card = noteCard(state);
        putIfNotBlank(data, "title", stringValue(card.get("display_title"), card.get("displayTitle"), card.get("title")));
        putIfNotBlank(data, "body", stringValue(card.get("desc"), card.get("note_desc"), nestedString(card, "note", "desc")));
        putIfNotBlank(data, "author", nestedString(card, "user", "nickname"));
        putIfNotBlank(data, "date", stringValue(card.get("time"), card.get("last_update_time"), nestedString(card, "note", "time")));
        putMetricText(data, "likes", firstMetric(countFromPath(card, "interact_info", "liked_count"),
                countFromPath(card, "interactInfo", "liked")));
        putMetricText(data, "collections", firstMetric(countFromPath(card, "interact_info", "collected_count"),
                countFromPath(card, "interactInfo", "collected")));
        putMetricText(data, "comments", firstMetric(countFromPath(card, "interact_info", "comment_count"),
                countFromPath(card, "interactInfo", "comment")));
    }

    private static void putIfNotBlank(Map<String, Object> data, String key, String value) {
        if (value != null && !value.isBlank()) data.put(key, value);
    }

    private static void putMetricText(Map<String, Object> data, String key, MetricValue value) {
        if (value != null && !value.missing) data.put(key, value.raw == null ? String.valueOf(value.value) : value.raw);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String stringValue(Object... values) {
        for (Object value : values) if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        return "";
    }

    private static String nestedString(Map<String, Object> map, String first, String second) {
        if (map.get(first) instanceof Map<?, ?> nested) {
            Object value = nested.get(second);
            if (value != null) return String.valueOf(value);
        }
        return "";
    }

    private static MetricValue firstMetric(MetricValue... values) {
        for (MetricValue value : values) if (value != null && !value.missing) return value;
        return MetricValue.missing();
    }

    private static MetricValue countFromPath(Map<String, Object> map, String... path) {
        Object current = map;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> nested)) return MetricValue.missing();
            current = nested.get(key);
        }
        return current == null ? MetricValue.missing() : CountParser.parse(String.valueOf(current));
    }

    private void applyDetail(Post post, Map<String, Object> data) {
        if (data.get("title") != null && !String.valueOf(data.get("title")).isBlank()) post.title = String.valueOf(data.get("title"));
        post.body = truncate(String.valueOf(data.getOrDefault("body", "")));
        if (data.get("author") != null && !String.valueOf(data.get("author")).isBlank()) post.author = String.valueOf(data.get("author"));
        if (data.get("date") != null && !String.valueOf(data.get("date")).isBlank()) post.publishedAt = String.valueOf(data.get("date"));
        String stats = String.valueOf(data.getOrDefault("stats", ""));
        post.likes = firstNonNull(CountParser.parse(String.valueOf(data.getOrDefault("likes", ""))),
                extractMetric(stats, List.of("点赞", "赞")), post.likes);
        post.collections = firstNonNull(CountParser.parse(String.valueOf(data.getOrDefault("collections", ""))),
                extractMetric(stats, List.of("收藏", "薯")), post.collections);
        post.comments = firstNonNull(CountParser.parse(String.valueOf(data.getOrDefault("comments", ""))),
                extractMetric(stats, List.of("评论", "评论数")), post.comments);
        post.collectedAt = OffsetDateTime.now();
        post.collectionStatus = "SUCCESS";
        post.coverage = new ArrayList<>(List.of("TITLE"));
        if (!post.body.isBlank()) post.coverage.add("BODY");
        post.coverageNote = "视频音画未处理";
        post.contentHash = sha256(post.title + "\n" + post.body);
    }

    private List<byte[]> captureImages(Page page, String postUrl) {
        try {
            List<Locator> images = page.locator("img").all();
            List<byte[]> shots = new ArrayList<>();
            int index = 0;
            for (Locator locator : images) {
                if (index >= config.getBrowser().getMaxImagesPerPost()) break;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> info = (Map<String, Object>) locator.evaluate("img => ({w: img.naturalWidth, h: img.naturalHeight, src: img.currentSrc || img.src})");
                    int w = ((Number)info.getOrDefault("w", 0)).intValue();
                    int h = ((Number)info.getOrDefault("h", 0)).intValue();
                    if (w < 220 || h < 120) continue;
                    byte[] png = locator.screenshot(new Locator.ScreenshotOptions().setType(ScreenshotType.PNG));
                    shots.add(png);
                    index++;
                } catch (Exception ignored) { continue; }
            }
            return shots;
        } catch (Exception e) {
            log.warn("Cannot capture images for {}", postUrl, e);
            return List.of();
        }
    }

    public void ensureInteractive(Page page) {
        String body = page.innerText("body").toLowerCase(Locale.ROOT);
        if (body.contains("验证码") || body.contains("verify") || body.contains("安全验证")) throw new LoginRequiredException("CAPTCHA_OR_VERIFICATION_REQUIRED");
        if (page.url().contains("/login")) throw new LoginRequiredException("LOGIN_REQUIRED");
    }

    public void ensureDetailAvailable(Page page, Post candidate) {
        String url = page.url();
        String body = page.innerText("body");
        String lowerBody = body.toLowerCase(Locale.ROOT);
        boolean blocked = url.contains("error_code=300031") || url.contains("/404")
                || body.contains("当前笔记暂时无法浏览") || body.contains("请打开小红书App扫码查看")
                || lowerBody.contains("current note cannot be viewed");
        if (blocked) {
            throw new NoteUnavailableException(candidate.postId, candidate.url,
                    "XHS_NOTE_UNAVAILABLE_OR_ACCESS_RESTRICTED");
        }
    }

    public static boolean isBlockedDetailTitle(String title) {
        return title != null && title.contains("当前笔记暂时无法浏览");
    }

    private void applyDetailPostImages(Post post, List<Map<String, Object>> images) {
        for (Map<String, Object> image : images) {
            ImageText t = new ImageText();
            t.imageIndex = ((Number)image.getOrDefault("i", 0)).intValue();
            t.sourceUrl = String.valueOf(image.getOrDefault("src", ""));
            t.status = "PENDING";
            post.imageText.add(t);
        }
    }

    private Post clone(Post p) {
        Post n = new Post();
        n.postRef = p.postRef; n.postId = p.postId; n.url = p.url; n.xsecToken = p.xsecToken; n.title = p.title; n.author = p.author;
        n.publishedAt = p.publishedAt; n.likes = p.likes; n.collections = p.collections; n.comments = p.comments;
        n.relevance = p.relevance; n.relevanceScore = p.relevanceScore; n.relevanceReason = p.relevanceReason;
        return n;
    }

    public static String postId(String url) {
        Matcher m = ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static String postRef(int index) { return "P" + String.format("%03d", index); }

    private static MetricValue extractMetric(String text, List<String> labels) {
        for (String label : labels) {
            int at = text.indexOf(label);
            if (at >= 0) {
                String tail = text.substring(at + label.length(), Math.min(text.length(), at + label.length() + 30));
                Matcher m = Pattern.compile("^\\D*?([0-9]+(?:\\.[0-9]+)?\\s*[万亿wW]?\\+?)").matcher(tail);
                if (m.find()) return CountParser.parse(m.group(1));
            }
        }
        return MetricValue.missing();
    }

    private static MetricValue firstNonNull(MetricValue... values) {
        for (MetricValue value : values) if (value != null && !value.missing) return value;
        return MetricValue.missing();
    }

    private static String firstLine(String text) {
        return Arrays.stream(text.split("\\n")).map(String::trim).filter(s -> !s.isBlank()).findFirst().orElse("");
    }

    private static String findLabel(String text, List<String> labels) {
        for (String label : labels) if (text.contains(label)) return label;
        return "";
    }

    private static String findDate(String text) {
        Matcher m = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}月\\d{1,2}日|\\d+天前|昨天|今天").matcher(text);
        return m.find() ? m.group() : "";
    }

    private static String truncate(String s) {
        return s.length() > 20000 ? s.substring(0, 20000) : s;
    }

    private static long random(long min, long max) {
        return ThreadLocalRandom.current().nextLong(min, Math.max(min + 1, max + 1));
    }

    private static String sha256(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder("sha256:");
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
