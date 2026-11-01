package com.limelight.nvstream.http;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

// The two serverinfo elements a host announces its hidden Benchmark app with. Robolectric for
// Android's XmlPullParser.
@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class BenchmarkAnnouncementParsingTest {

    private static final String UUID = "B3C4F1A2-7E5D-4B8A-9C61-2D0E8F4A6B37";

    private static String serverInfo(String extra) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root status_code=\"200\">"
                + "<hostname>ATLAS</hostname><uniqueid>abc</uniqueid>" + extra + "</root>";
    }

    @Test
    public void bothFieldsAreRead() throws Exception {
        String info = serverInfo("<BenchmarkAppId>4242</BenchmarkAppId><BenchmarkAppUuid>" + UUID + "</BenchmarkAppUuid>");

        assertEquals(4242, NvHTTP.parseBenchmarkAppId(info));
        assertEquals(UUID, NvHTTP.parseBenchmarkAppUuid(info));
    }

    @Test
    public void aHostWithoutTheAppAnnouncesNothing() throws Exception {
        String info = serverInfo("");

        assertEquals(0, NvHTTP.parseBenchmarkAppId(info));
        assertNull(NvHTTP.parseBenchmarkAppUuid(info));
    }

    @Test
    public void aGarbageIdReadsAsAbsent() throws Exception {
        assertEquals(0, NvHTTP.parseBenchmarkAppId(serverInfo("<BenchmarkAppId>soon</BenchmarkAppId>")));
        assertEquals(0, NvHTTP.parseBenchmarkAppId(serverInfo("<BenchmarkAppId></BenchmarkAppId>")));
        assertEquals(0, NvHTTP.parseBenchmarkAppId(serverInfo("<BenchmarkAppId>-1</BenchmarkAppId>")));
        assertEquals(0, NvHTTP.parseBenchmarkAppId(serverInfo("<BenchmarkAppId>99999999999</BenchmarkAppId>")));
    }

    @Test
    public void anEmptyUuidReadsAsAbsent() throws Exception {
        assertNull(NvHTTP.parseBenchmarkAppUuid(serverInfo("<BenchmarkAppUuid></BenchmarkAppUuid>")));
    }

    @Test
    public void updateCopiesTheAnnouncementAndTheCodecSupport() {
        ComputerDetails fresh = new ComputerDetails();
        fresh.benchmarkAppId = 7;
        fresh.benchmarkAppUuid = UUID;
        fresh.serverCodecModeSupport = 0x101;

        ComputerDetails known = new ComputerDetails();
        known.update(fresh);

        assertEquals(7, known.benchmarkAppId);
        assertEquals(UUID, known.benchmarkAppUuid);
        assertEquals(0x101, known.serverCodecModeSupport);

        // The host withdrew it
        known.update(new ComputerDetails());
        assertEquals(0, known.benchmarkAppId);
        assertNull(known.benchmarkAppUuid);
    }
}
