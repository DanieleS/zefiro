package com.limelight.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;


import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The published store against a stand-in for GitHub Pages: a view republished under the same file
 * name reaches the next session, and an unchanged index costs a 304.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class PublishedViewStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private MockWebServer server;
    private String baseUrl;
    /** Path → body served; the index's ETag is derived from its body, as Pages does. */
    private final Map<String, String> files = new HashMap<>();
    /** Each request as "path conditional|plain→status"; the dispatcher runs on the server's thread. */
    private final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());

    @Before
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath().substring(1);
                String body = files.get(path);
                String asked = request.getHeader("If-None-Match");
                MockResponse response;
                if (body == null) {
                    response = new MockResponse().setResponseCode(404);
                } else {
                    String etag = "\"" + sha256(body).substring(0, 16) + "\"";
                    response = etag.equals(asked)
                            ? new MockResponse().setResponseCode(304)
                            : new MockResponse().setHeader("ETag", etag).setBody(body);
                }
                requests.add(path + " " + (asked != null ? "conditional" : "plain") + "→" + response.getStatus().split(" ")[1]);
                return response;
            }
        });
        server.start();
        baseUrl = server.url("/").toString();
    }

    @After
    public void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    public void aViewRepublishedUnderTheSameNameReachesTheNextSession() throws Exception {
        File root = folder.newFolder("published");
        publish("<p>old</p>");
        assertEquals("<p>old</p>", newSession(root).find(ff7()));

        publish("<p>new</p>");
        assertEquals("<p>new</p>", newSession(root).find(ff7()));
    }

    @Test
    public void anUnchangedIndexIsAnsweredWithNoBody() throws Exception {
        File root = folder.newFolder("published");
        publish("<p>view</p>");
        newSession(root).find(ff7());
        requests.clear();

        assertEquals("<p>view</p>", newSession(root).find(ff7()));
        assertEquals(List.of("index.json conditional→304"), requests);
    }

    @Test
    public void theIndexIsCheckedOncePerSession() throws Exception {
        File root = folder.newFolder("published");
        publish("<p>view</p>");
        TelemetryViewStore store = newSession(root);
        store.find(ff7());
        requests.clear();

        // The game detaches and attaches again: the view is resolved again, from what is kept.
        assertEquals("<p>view</p>", store.find(ff7()));
        assertEquals(java.util.Collections.emptyList(), requests);
    }

    @Test
    public void aContractTheIndexDoesNotKnowIsAskedAboutOncePerSession() throws Exception {
        File root = folder.newFolder("published");
        publish("<p>view</p>");
        TelemetryViewStore store = newSession(root);
        assertNull(store.find(contract("sea-of-stars", "2.0")));
        assertNull(store.find(contract("sea-of-stars", "2.0")));

        assertEquals(List.of("index.json plain→200"), requests);
    }

    @Test
    public void withoutANetworkTheKeptViewIsUsed() throws Exception {
        File root = folder.newFolder("published");
        publish("<p>view</p>");
        newSession(root).find(ff7());
        server.shutdown();

        assertEquals("<p>view</p>", newSession(root).find(ff7()));
    }

    private TelemetryViewStore newSession(File root) {
        return new TelemetryViewStore.Published(root, baseUrl);
    }

    private void publish(String html) throws Exception {
        String file = "ff7-rebirth@1.3-2.0.html";
        files.put(file, html);
        JSONObject view = new JSONObject()
                .put("contract", "ff7-rebirth")
                .put("range", "^1.3")
                .put("file", file)
                .put("sha256", sha256(html));
        files.put("index.json", new JSONObject()
                .put("format", 1)
                .put("views", new org.json.JSONArray().put(view))
                .toString());
    }

    private static TelemetryViewStore.Contract ff7() throws Exception {
        return contract("ff7-rebirth", "1.3");
    }

    private static TelemetryViewStore.Contract contract(String id, String version) throws Exception {
        return TelemetryViewStore.Contract.from(new JSONObject()
                .put("contract", new JSONObject().put("id", id).put("version", version)));
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
